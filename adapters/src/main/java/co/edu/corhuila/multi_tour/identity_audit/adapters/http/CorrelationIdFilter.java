package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reuses the {@code X-Correlation-Id} of the caller when it is safe to write in a log line, otherwise
 * creates one; returns it, puts it in the MDC so every log line carries it and offers it as the
 * {@code traceId} of the error envelope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "traceId";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  /** The trace id of the request being served; the filter always runs first, so "unknown" is a bug to see. */
  static String current() {
    String id = MDC.get(MDC_KEY);
    return id != null ? id : "unknown";
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String sent = request.getHeader(HEADER);
    String id = sent != null && SAFE.matcher(sent).matches() ? sent : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
