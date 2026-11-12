package co.edu.corhuila.multi_tour.identity_audit.adapters.cash;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CashBoxes;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CashBoxesUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Reads the cash boxes of a tenant from {@code cash-reporting} with the token of this service (spec 010 D-03, ADR-004 D4): the route lists the
 * open cash days across tenants, page by page, and this adapter keeps the ones of the tenant it was asked about. It keeps nothing between calls,
 * so a box opened a moment ago is seen. Whatever goes wrong (not configured, down, a refusal, an answer it cannot read, a list that never ends) is
 * {@link CashBoxesUnavailableException}: a change that depends on it is never decided on a guess.
 */
public final class HttpCashBoxes implements CashBoxes {

  private static final Logger LOG = LoggerFactory.getLogger(HttpCashBoxes.class);
  private static final String PATH = "/api/v1/cash-reporting/internal/cash-days/open";
  private static final int PAGE_SIZE = 100;
  /** A bound on the reading: 50 pages of 100 are 5000 open cash days, far beyond what a day can hold. */
  private static final int MAX_PAGES = 50;

  private final String baseUrl;
  private final String serviceToken;
  private final ObjectMapper json;
  private final Duration timeout;
  private final Duration deadline;
  private final HttpClient client;

  public HttpCashBoxes(String baseUrl, String serviceToken, ObjectMapper json, Duration timeout) {
    this(baseUrl, serviceToken, json, timeout, Duration.ofSeconds(15));
  }

  /** {@code deadline} bounds the whole reading, all its pages together, so a slow service holds the request for that long at most. */
  public HttpCashBoxes(String baseUrl, String serviceToken, ObjectMapper json, Duration timeout, Duration deadline) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
    this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    this.json = json;
    this.timeout = timeout;
    this.deadline = deadline;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public boolean anyOpenOrPendingClosing(String tenantId) {
    if (baseUrl.isEmpty() || serviceToken.isEmpty()) {
      throw unavailable("the address or the token of the service is not configured", null);
    }
    long limit = System.nanoTime() + deadline.toNanos();
    for (int page = 1; page <= MAX_PAGES; page++) {
      if (System.nanoTime() > limit) {
        throw unavailable("the reading took longer than its deadline", null);
      }
      JsonNode body = read(page);
      JsonNode data = body == null ? null : body.get("data");
      JsonNode totalPages = body == null ? null : body.path("meta").get("totalPages");
      // an answer that is not a list with its paging is a failure, never "no open cash": the zone would change without having read anything
      if (data == null || !data.isArray() || totalPages == null || !totalPages.canConvertToInt() || !totalPages.isIntegralNumber()) {
        throw unavailable("the answer is not a list with its paging", null);
      }
      for (JsonNode item : data) {
        JsonNode tenant = item.get("tenantId");
        if (tenant == null || !tenant.isTextual()) {
          throw unavailable("an item has no tenant", null);
        }
        if (tenantId.equals(tenant.asText())) {
          return true;
        }
      }
      if (page >= totalPages.asInt()) {
        return false;
      }
    }
    throw unavailable("more pages than the bound", null);
  }

  private JsonNode read(int page) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + PATH + "?page=" + page + "&limit=" + PAGE_SIZE))
          .timeout(timeout)
          .header("Authorization", "Bearer " + serviceToken)
          .GET();
      String trace = MDC.get("traceId");
      if (trace != null) {
        request.header("X-Correlation-Id", trace);
      }
      HttpResponse<String> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (answer.statusCode() != 200) {
        throw unavailable("the service answered " + answer.statusCode(), null);
      }
      return json.readTree(answer.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted", e);
    } catch (IOException | IllegalArgumentException e) {
      throw unavailable("no usable answer", e);
    }
  }

  /** What failed goes to the log as a state or a class, never as a header or a body: the token and the data of the other service stay out. */
  private static CashBoxesUnavailableException unavailable(String why, Exception cause) {
    LOG.warn("The cash boxes could not be read: {}{}", why, cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")");
    return new CashBoxesUnavailableException();
  }
}
