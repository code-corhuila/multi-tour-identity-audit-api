package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CashBoxes;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CashBoxesUnavailableException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** PUT /tenant/localization (spec 010, HU-IDA-004), on the ports kept in memory: every status of the contract and the isolation of the tenants. */
class LocalizationRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/identity-audit/tenant/localization";
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

  /** The cash boxes: the tenants that have an open one, and whether the other service answers. */
  static final class FakeCash implements CashBoxes {
    final java.util.Set<String> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile boolean down;

    @Override
    public boolean anyOpenOrPendingClosing(String tenantId) {
      if (down) {
        throw new CashBoxesUnavailableException();
      }
      return open.contains(tenantId);
    }
  }

  @TestConfiguration
  static class InMemory {
    @Bean
    @Primary
    FakeTenants fakeTenants() {
      return new FakeTenants();
    }

    @Bean
    @Primary
    FakeCash fakeCash() {
      return new FakeCash();
    }
  }

  @Autowired FakeTenants fake;
  @Autowired FakeCash cash;

  private static Tenant active(String id, String identifier) {
    return Tenant.registerPending(id, identifier, "Tours " + identifier, "America/Bogota", "CO", T0)
        .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0);
  }

  @BeforeEach
  void twoActiveTenantsAndNoCash() {
    fake.tenants.clear();
    fake.tenants.put(A, active(A, "acme-tours"));
    fake.tenants.put(B, active(B, "other-tours"));
    cash.open.clear();
    cash.down = false;
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims("00000000-0000-4000-8000-0000000000cc", 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification administratorOf(String tenantId) {
    return as("TENANT_ADMINISTRATOR", tenantId);
  }

  private static Map<String, Object> body(String zone, String country) {
    Map<String, Object> body = new HashMap<>();
    if (zone != null) {
      body.put("timeZone", zone);
    }
    if (country != null) {
      body.put("country", country);
    }
    return body;
  }

  private static Response change(RequestSpecification who, Object body) {
    return who.filter(Contract.filter()).body(body).put(ROUTE);
  }

  private static Response refused(RequestSpecification who, Object body) {
    return who.filter(Contract.answers()).body(body).put(ROUTE);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_the_zone_and_the_country_change_for_the_tenant_of_the_token_only() {
    Response response = change(administratorOf(A), body("America/Mexico_City", "MX"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantId")).isEqualTo(A);
    assertThat(response.jsonPath().getString("timeZone")).isEqualTo("America/Mexico_City");
    assertThat(response.jsonPath().getString("country")).isEqualTo("MX");
    assertThat(fake.tenants.get(A).timeZone()).isEqualTo("America/Mexico_City");
    assertThat(fake.tenants.get(B).timeZone()).isEqualTo("America/Bogota");
    assertThat(change(administratorOf(B), body(null, "PE")).jsonPath().getString("country")).isEqualTo("PE");
    assertThat(fake.tenants.get(A).country()).isEqualTo("MX");
  }

  @Test
  void C_only_the_tenant_administrator_changes_the_localization() {
    RequestSpecification[] others = {
        as("PLATFORM_ADMINISTRATOR", null), as("SERVICE", null), as("FINAL_CUSTOMER", A), as("MANAGER", A), as("OPERATIONAL_COLLABORATOR", A)};
    for (RequestSpecification other : others) {
      assertRefused(refused(other, body(null, "MX")), 403, "FORBIDDEN");
    }
    assertRefused(refused(administratorOf(null), body(null, "MX")), 403, "FORBIDDEN");
    assertThat(fake.tenants.get(A).country()).isEqualTo("CO");
  }

  @Test
  void C_the_exceptional_token_is_forbidden() {
    String token = Tokens.valid(Tokens.claims("00000000-0000-4000-8000-0000000000cc", 600, List.of("PLATFORM_ADMINISTRATOR"), A)
        .claim("access", "exceptional").claim("scope", List.of("OPERATIONS")).claim("authorizationId", "3f2b8c1e-1111-4222-8333-444455556666"));
    assertRefused(refused(given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON), body(null, "MX")), 403, "FORBIDDEN");
    assertThat(fake.tenants.get(A).country()).isEqualTo("CO");
  }

  @Test
  void C_a_bad_body_is_400_by_field() {
    Response zone = refused(administratorOf(A), body("Mars/Olympus", null));
    assertRefused(zone, 400, "VALIDATION_ERROR");
    assertThat(zone.jsonPath().getString("details[0].field")).isEqualTo("timeZone");
    Response country = refused(administratorOf(A), body(null, "ZZ"));
    assertRefused(country, 400, "VALIDATION_ERROR");
    assertThat(country.jsonPath().getString("details[0].field")).isEqualTo("country");
    assertRefused(refused(administratorOf(A), Map.of()), 400, "VALIDATION_ERROR");
    Map<String, Object> withTenant = body(null, "MX");
    withTenant.put("tenantId", B);
    assertRefused(refused(administratorOf(A), withTenant), 400, "VALIDATION_ERROR");
    assertThat(fake.tenants.get(A).country()).isEqualTo("CO");
    assertThat(fake.tenants.get(B).country()).isEqualTo("CO");
  }

  @Test
  void C_an_open_cash_blocks_the_zone_but_not_the_country() {
    cash.open.add(A);

    assertRefused(refused(administratorOf(A), body("America/Lima", null)), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(fake.tenants.get(A).timeZone()).isEqualTo("America/Bogota");
    assertThat(change(administratorOf(A), body(null, "PE")).statusCode()).isEqualTo(200);
  }

  @Test
  void C_an_unreadable_cash_state_answers_503_and_changes_nothing() {
    cash.down = true;

    assertRefused(refused(administratorOf(A), body("America/Lima", null)), 503, "SERVICE_UNAVAILABLE");
    assertThat(fake.tenants.get(A).timeZone()).isEqualTo("America/Bogota");
  }

  @Test
  void C_an_inactive_tenant_is_422_and_the_same_values_answer_200_and_change_nothing() {
    assertThat(change(administratorOf(A), body("America/Bogota", "CO")).statusCode()).isEqualTo(200);
    assertThat(fake.tenants.get(A).updatedAt()).isEqualTo(T0);
    fake.tenants.put(A, fake.tenants.get(A).inactivate("platform", "reason", T0));

    assertRefused(refused(administratorOf(A), body(null, "MX")), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_a_tenant_that_does_not_exist_is_404() {
    assertRefused(refused(administratorOf("00000000-0000-4000-8000-0000000000ff"), body(null, "MX")), 404, "NOT_FOUND");
  }
}
