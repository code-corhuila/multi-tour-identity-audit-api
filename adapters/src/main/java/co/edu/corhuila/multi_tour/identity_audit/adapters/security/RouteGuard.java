package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

/** Which routes need a token. */
final class RouteGuard {

  private RouteGuard() {}

  static boolean needsToken(String uri) {
    return false;
  }
}
