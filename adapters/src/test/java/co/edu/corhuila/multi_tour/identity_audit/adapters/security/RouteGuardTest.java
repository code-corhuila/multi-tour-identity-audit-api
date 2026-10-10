package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RouteGuardTest {

  @Test
  void C_the_api_needs_a_token() {
    assertThat(RouteGuard.needsToken("/api/v1/identity-audit/tenants")).isTrue();
    assertThat(RouteGuard.needsToken("/API/v1/identity-audit/tenants")).isTrue();
    assertThat(RouteGuard.needsToken("/api/v2/anything")).isTrue();
  }

  @Test
  void C_only_the_health_route_and_the_exact_public_profile_are_open() {
    assertThat(RouteGuard.needsToken("/health")).isFalse();
    assertThat(RouteGuard.needsToken("/api/v1/identity-audit/public/tenants/acme-tours")).isFalse();
    assertThat(RouteGuard.needsToken("/api/v1/identity-audit/public/tenants/Acme")).isTrue();
    assertThat(RouteGuard.needsToken("/api/v1/identity-audit/public/tenants/acme/extra")).isTrue();
    assertThat(RouteGuard.needsToken("/api/v1/identity-audit/public/other")).isTrue();
  }

  @Test
  void C_a_path_written_in_a_tricky_way_is_never_open() {
    for (String uri : new String[] {
        "//api/v1/identity-audit/tenants",
        "/api/v1/identity-audit/public/../tenants",
        "/api/v1/identity-audit/public/%2e%2e/tenants",
        "/api/v1/identity-audit/public/tenants/acme-tours;x=1",
        "/health/../api/v1/identity-audit/tenants",
        "/probe/./x"}) {
      assertThat(RouteGuard.needsToken(uri)).as(uri).isTrue();
    }
  }

  @Test
  void C_a_plain_unknown_route_is_left_to_the_404() {
    assertThat(RouteGuard.needsToken("/nowhere")).isFalse();
    assertThat(RouteGuard.needsToken("/probe/echo")).isFalse();
  }
}
