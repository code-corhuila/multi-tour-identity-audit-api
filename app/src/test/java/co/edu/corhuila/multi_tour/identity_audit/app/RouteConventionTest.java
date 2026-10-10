package co.edu.corhuila.multi_tour.identity_audit.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The token guard opens every route outside {@code /api/}, so a business route mapped there would be public
 * without anyone noticing: this test makes the convention fail instead of relying on a comment.
 */
class RouteConventionTest extends ShellTest {

  /** The routes that are meant to be open, and the ones that exist only in the tests. */
  private static final Set<String> OPEN = Set.of("/health", "/error");

  private static final String TEST_ONLY = "/probe/";

  @Autowired RequestMappingHandlerMapping mapping;

  @Test
  void C_every_mapped_route_is_under_the_api_or_is_one_of_the_open_ones() {
    Set<String> patterns =
        mapping.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
            .collect(Collectors.toSet());
    assertThat(patterns).contains("/health", "/error");
    assertThat(patterns)
        .allSatisfy(
            route ->
                assertThat(route.startsWith("/api/") || OPEN.contains(route) || route.startsWith(TEST_ONLY))
                    .as("route %s must live under /api/", route)
                    .isTrue());
  }
}
