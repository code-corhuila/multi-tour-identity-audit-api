package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Judges the role before the handler reads the request (stub: it lets everything pass). */
public final class RouteAuthorization implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    return true;
  }
}
