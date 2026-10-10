package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which routes need a token. Whatever is not the health route or the exact public profile and looks like
 * the API, or is written in a way that a path could be disguised (dots, encoded characters, semicolons,
 * doubled slashes), needs one; a plain route that does not exist is left to the 404.
 *
 * <p>Every business route MUST live under {@code /api/}: any other mapped route is open. The test
 * {@code RouteConventionTest} fails if a handler is mapped outside {@code /api/} without being listed there.
 */
final class RouteGuard {

  private static final Pattern PUBLIC_PROFILE =
      Pattern.compile("/api/v1/identity-audit/public/tenants/[a-z0-9-]{1,40}");

  private RouteGuard() {}

  /** Whether the path is written in a way that a path could be disguised (stub). */
  static boolean isDisguised(String uri) {
    return false;
  }

  static boolean needsToken(String uri) {
    if (uri.equals("/health") || PUBLIC_PROFILE.matcher(uri).matches()) {
      return false;
    }
    String path = uri.toLowerCase(Locale.ROOT);
    return path.contains("/api/")
        || path.contains("..")
        || path.contains("/.")
        || path.contains("%")
        || path.contains(";")
        || path.contains("//");
  }
}
