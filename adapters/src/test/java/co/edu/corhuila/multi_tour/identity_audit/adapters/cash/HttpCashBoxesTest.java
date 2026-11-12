package co.edu.corhuila.multi_tour.identity_audit.adapters.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CashBoxesUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Spec 010 RN-IDA-API-102 and -103: the open cash boxes are read from the other service at the moment, page by page, and a failure is never a guess. */
class HttpCashBoxesTest {

  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> correlation = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String start(java.util.function.Function<String, Object[]> answer) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/cash-reporting/internal/cash-days/open", exchange -> {
      requests.add(exchange.getRequestURI().getRawQuery());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
      Object[] reply = answer.apply(exchange.getRequestURI().getRawQuery());
      byte[] body = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders((Integer) reply[0], body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static String page(int number, int totalPages, String... tenants) {
    StringBuilder items = new StringBuilder();
    for (String tenant : tenants) {
      items.append(items.length() == 0 ? "" : ",").append("{\"tenantId\":\"").append(tenant)
          .append("\",\"businessDate\":\"2026-11-11\",\"timeZone\":\"America/Bogota\",\"origin\":\"AUTOMATIC_CREATION\",\"hasOperationalOpening\":false,\"pendingClosure\":false}");
    }
    return "{\"data\":[" + items + "],\"meta\":{\"page\":" + number + ",\"limit\":100,\"total\":3,\"totalPages\":" + totalPages + "}}";
  }

  private HttpCashBoxes adapter(String base, String token) {
    return new HttpCashBoxes(base, token, new ObjectMapper(), Duration.ofSeconds(2));
  }

  @Test
  void A_the_tenant_is_found_on_a_later_page_and_the_service_token_goes_with_the_request() throws IOException {
    String base = start(query -> query.contains("page=2")
        ? new Object[] {200, page(2, 2, A)}
        : new Object[] {200, page(1, 2, B, B)});

    assertThat(adapter(base, "service-token").anyOpenOrPendingClosing(A)).isTrue();
    assertThat(requests).hasSize(2);
    assertThat(authorization.get()).isEqualTo("Bearer service-token");
  }

  @Test
  void A_a_tenant_with_no_open_cash_is_false_after_reading_every_page() throws IOException {
    String base = start(query -> query.contains("page=2") ? new Object[] {200, page(2, 2, B)} : new Object[] {200, page(1, 2, B)});

    assertThat(adapter(base, "t").anyOpenOrPendingClosing(A)).isFalse();
    assertThat(requests).hasSize(2);
  }

  @Test
  void C_an_unreadable_cash_state_answers_503_and_changes_nothing() throws IOException {
    String base = start(query -> new Object[] {500, "{\"error\":\"INTERNAL_ERROR\"}"});
    assertThatThrownBy(() -> adapter(base, "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);

    String unauthorized = start(query -> new Object[] {401, "{}"});
    assertThatThrownBy(() -> adapter(unauthorized, "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);

    String notJson = start(query -> new Object[] {200, "not json"});
    assertThatThrownBy(() -> adapter(notJson, "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);
  }

  @Test
  void C_a_service_that_is_down_or_not_configured_is_unavailable_and_never_a_guess() {
    assertThatThrownBy(() -> adapter("http://127.0.0.1:1", "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);
    assertThatThrownBy(() -> adapter("", "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);
    assertThatThrownBy(() -> adapter("http://127.0.0.1:1", "").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);
  }

  @Test
  void A_a_never_ending_list_is_cut_and_is_unavailable() throws IOException {
    String base = start(query -> new Object[] {200, page(1, 100000, B)});

    assertThatThrownBy(() -> adapter(base, "t").anyOpenOrPendingClosing(A)).isInstanceOf(CashBoxesUnavailableException.class);
    assertThat(requests.size()).isLessThanOrEqualTo(50);
  }
}
