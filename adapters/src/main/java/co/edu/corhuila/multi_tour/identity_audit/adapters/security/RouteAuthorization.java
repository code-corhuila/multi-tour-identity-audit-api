package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Judges the role before the handler reads the request: {@code /internal/} routes answer only {@code SERVICE}
 * (ADR-004 D5), every other route with a token only the Platform Administrator (RN-IDA-API-01).
 */
public final class RouteAuthorization implements HandlerInterceptor {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object caller = request.getAttribute(JwtAuthFilter.CALLER);
    Role required = request.getRequestURI().startsWith(INTERNAL) ? Role.SERVICE : Role.PLATFORM_ADMINISTRATOR;
    if (!(caller instanceof Caller who) || !who.roles().contains(required)) {
      throw new ForbiddenException();
    }
    return true;
  }
}
