package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** GET /tenant/localization (spec 010, rule RN-IDA-API-107, HU-IDA-004), on the ports kept in memory: who reads and whose tenant. */
class LocalizationReadRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/identity-audit/tenant/localization";
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

  @TestConfiguration
  static class InMemory {
    @Bean
    @Primary
    FakeTenants fakeTenants() {
      return new FakeTenants();
    }
  }

  @Autowired FakeTenants fake;

  private static Tenant active(String id, String identifier, String zone, String country) {
    return Tenant.registerPending(id, identifier, "Tours " + identifier, zone, country, T0)
        .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0);
  }

  @BeforeEach
  void twoTenantsWithTheirOwnLocalization() {
    fake.tenants.clear();
    fake.tenants.put(A, active(A, "acme-tours", "America/Bogota", "CO"));
    fake.tenants.put(B, active(B, "other-tours", "America/Mexico_City", "MX"));
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims("00000000-0000-4000-8000-0000000000cc", 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static Response read(RequestSpecification who) {
    return who.filter(Contract.filter()).get(ROUTE);
  }

  private static Response refused(RequestSpecification who) {
    return who.filter(Contract.answers()).get(ROUTE);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_the_administrator_reads_the_zone_and_the_country_of_their_own_tenant() {
    Response response = read(as("TENANT_ADMINISTRATOR", A));

    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantId")).isEqualTo(A);
    assertThat(response.jsonPath().getString("timeZone")).isEqualTo("America/Bogota");
    assertThat(response.jsonPath().getString("country")).isEqualTo("CO");
    Response other = read(as("TENANT_ADMINISTRATOR", B));
    assertThat(other.jsonPath().getString("tenantId")).isEqualTo(B);
    assertThat(other.jsonPath().getString("timeZone")).isEqualTo("America/Mexico_City");
    assertThat(other.jsonPath().getString("country")).isEqualTo("MX");
  }

  @Test
  void C_an_inactive_tenant_is_read_all_the_same() {
    fake.tenants.put(A, fake.tenants.get(A).inactivate("platform-admin", "unpaid", T0));

    Response response = read(as("TENANT_ADMINISTRATOR", A));

    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("INACTIVE");
    assertThat(response.jsonPath().getString("timeZone")).isEqualTo("America/Bogota");
  }

  @Test
  void C_only_the_tenant_administrator_reads_the_localization() {
    RequestSpecification[] others = {
        as("PLATFORM_ADMINISTRATOR", null), as("SERVICE", null), as("FINAL_CUSTOMER", A), as("MANAGER", A), as("OPERATIONAL_COLLABORATOR", A)};
    for (RequestSpecification other : others) {
      assertRefused(refused(other), 403, "FORBIDDEN");
    }
    assertRefused(refused(as("TENANT_ADMINISTRATOR", null)), 403, "FORBIDDEN");
  }

  @Test
  void C_the_exceptional_token_is_forbidden() {
    String token = Tokens.valid(Tokens.claims("00000000-0000-4000-8000-0000000000cc", 600, List.of("PLATFORM_ADMINISTRATOR"), A)
        .claim("access", "exceptional").claim("scope", List.of("OPERATIONS")).claim("authorizationId", "3f2b8c1e-1111-4222-8333-444455556666"));
    assertRefused(refused(given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)), 403, "FORBIDDEN");
  }

  @Test
  void C_a_token_without_credentials_is_401() {
    assertRefused(given().filter(Contract.answers()).get(ROUTE), 401, "UNAUTHORIZED");
  }

  @Test
  void C_a_tenant_that_does_not_exist_is_404() {
    fake.tenants.clear();

    assertRefused(refused(as("TENANT_ADMINISTRATOR", A)), 404, "NOT_FOUND");
  }
}
