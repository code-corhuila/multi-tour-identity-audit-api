package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Judges the role before the handler reads the request: {@code /internal/} routes answer only {@code SERVICE}
 * (ADR-004 D5), every other route with a token only the Platform Administrator (RN-IDA-API-01). The role is
 * chosen on the path as it is written, while the route that answers is chosen on the normalized one, so a path
 * written in a disguised way (see {@link RouteGuard#isDisguised}) is refused to everyone: no route needs one.
 */
public final class RouteAuthorization implements HandlerInterceptor {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String uri = request.getRequestURI();
    Role required = uri.startsWith(INTERNAL) ? Role.SERVICE : Role.PLATFORM_ADMINISTRATOR;
    if (RouteGuard.isDisguised(uri)
        || !(request.getAttribute(JwtAuthFilter.CALLER) instanceof Caller who)
        || !who.roles().contains(required)) {
      throw new ForbiddenException();
    }
    return true;
  }
}
