package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The role is judged on the path as it is written, and a path written in a disguised way is refused whoever asks. */
class RouteAuthorizationTest {

  private static final Caller SERVICE = new Caller("onboard-tenant", null, Set.of(Role.SERVICE));
  private static final Caller ADMIN = new Caller("account-1", null, Set.of(Role.PLATFORM_ADMINISTRATOR));
  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String PLATFORM = "/api/v1/identity-audit/tenants";

  private final RouteAuthorization authorization = new RouteAuthorization();

  private static HttpServletRequest request(String uri, Caller caller) {
    return (HttpServletRequest) Proxy.newProxyInstance(
        RouteAuthorizationTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getRequestURI" -> uri;
          case "getAttribute" -> JwtAuthFilter.CALLER.equals(args[0]) ? caller : null;
          default -> null;
        });
  }

  private boolean passes(String uri, Caller caller) {
    return authorization.preHandle(request(uri, caller), null, null);
  }

  @Test
  void C_the_internal_routes_are_for_the_service_and_the_others_for_the_platform_administrator() {
    assertThatCode(() -> passes(INTERNAL, SERVICE)).doesNotThrowAnyException();
    assertThatCode(() -> passes(PLATFORM + "/abc", ADMIN)).doesNotThrowAnyException();
    assertThatThrownBy(() -> passes(INTERNAL, ADMIN)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(PLATFORM, SERVICE)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void C_a_request_with_no_caller_or_with_a_role_of_no_use_is_refused() {
    assertThatThrownBy(() -> passes(INTERNAL, null)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(PLATFORM, null)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void C_a_disguised_path_is_refused_to_the_platform_administrator_and_to_the_service_alike() {
    for (String disguised : new String[] {
        "/api/v1/identity-audit/internal;x=1/tenants", "/api/v1/identity-audit/%69nternal/tenants",
        "/api/v1/identity-audit//internal/tenants", "/api/v1/identity-audit/./internal/tenants",
        "/api/v1/identity-audit/public/../internal/tenants", "/api/v1/identity-audit/internal/tenants;jsessionid=1"}) {
      assertThatThrownBy(() -> passes(disguised, ADMIN)).as("admin on %s", disguised).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> passes(disguised, SERVICE)).as("service on %s", disguised).isInstanceOf(ForbiddenException.class);
    }
  }

  @Test
  void C_the_visual_identity_route_is_for_the_tenant_administrator_and_only_for_it() {
    Caller tenantAdmin = new Caller("account-2", "00000000-0000-4000-8000-00000000000a", Set.of(Role.TENANT_ADMINISTRATOR));
    String route = "/api/v1/identity-audit/tenant/visual-identity";
    assertThatCode(() -> passes(route, tenantAdmin)).doesNotThrowAnyException();
    assertThatThrownBy(() -> passes(route, ADMIN)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(route, SERVICE)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(PLATFORM, tenantAdmin)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(INTERNAL, tenantAdmin)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> passes(route + ";x=1", tenantAdmin)).isInstanceOf(ForbiddenException.class);
  }
}