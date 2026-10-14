package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.VisualIdentity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
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

/** PUT /tenant/visual-identity and the public profile that carries it, on the port kept in memory. */
class VisualIdentityRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/identity-audit/tenant/visual-identity";
  private static final String PROFILE = "/api/v1/identity-audit/public/tenants/";
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

  private static Tenant active(String id, String identifier) {
    return Tenant.registerPending(id, identifier, "Tours " + identifier, "America/Bogota", "CO", T0)
        .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0);
  }

  @BeforeEach
  void twoActiveTenants() {
    fake.tenants.clear();
    fake.tenants.put(A, active(A, "acme-tours"));
    fake.tenants.put(B, active(B, "other-tours"));
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims("account-7", 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification administratorOf(String tenantId) {
    return as("TENANT_ADMINISTRATOR", tenantId);
  }

  private static Map<String, Object> colors(String primary, String secondary) {
    Map<String, Object> body = new HashMap<>();
    body.put("primaryColor", primary);
    body.put("secondaryColor", secondary);
    return body;
  }

  private static Response set(RequestSpecification who, Object body) {
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
  void C_visual_identity_is_set_for_the_tenant_of_the_token_and_answers_the_tenant_in_capitals() {
    Response response = set(administratorOf(A), colors("#146b5d", "#0F564b"));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantId")).isEqualTo(A);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
    assertThat(response.jsonPath().getString("visualIdentity.secondaryColor")).isEqualTo("#0F564B");
    assertThat(fake.tenants.get(A).visualIdentity()).isEqualTo(new VisualIdentity("#146B5D", "#0F564B"));
  }

  @Test
  void C_visual_identity_changes_only_the_tokens_tenant() {
    assertThat(set(administratorOf(A), colors("#111111", "#222222")).statusCode()).isEqualTo(200);
    assertThat(fake.tenants.get(B).visualIdentity()).isNull();
    assertThat(set(administratorOf(B), colors("#333333", "#444444")).statusCode()).isEqualTo(200);
    assertThat(fake.tenants.get(A).visualIdentity()).isEqualTo(new VisualIdentity("#111111", "#222222"));
    assertThat(fake.tenants.get(B).visualIdentity()).isEqualTo(new VisualIdentity("#333333", "#444444"));
  }

  @Test
  void C_visual_identity_with_a_tenant_id_in_the_body_is_400() {
    Map<String, Object> body = colors("#111111", "#222222");
    body.put("tenantId", B);
    Response response = refused(administratorOf(A), body);
    assertRefused(response, 400, "VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("tenantId");
    assertThat(fake.tenants.get(A).visualIdentity()).isNull();
    assertThat(fake.tenants.get(B).visualIdentity()).isNull();
  }

  @Test
  void C_visual_identity_refuses_other_roles_before_it_reads_the_body() {
    RequestSpecification[] others = {
        as("PLATFORM_ADMINISTRATOR", null), as("SERVICE", null), as("FINAL_CUSTOMER", A), as("MANAGER", A)};
    for (RequestSpecification other : others) {
      assertRefused(refused(other, colors("#111111", "#222222")), 403, "FORBIDDEN");
    }
    assertRefused(refused(as("SERVICE", null), "{not json"), 403, "FORBIDDEN");
    assertRefused(refused(administratorOf(null), colors("#111111", "#222222")), 403, "FORBIDDEN");
    assertThat(fake.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void C_visual_identity_with_a_bad_color_is_400_by_field() {
    for (String bad : new String[] {"146B5D", "#146B5", "#146B5DA", "#14GB5D", "#146B5D\n", " ", "", null}) {
      Response primary = refused(administratorOf(A), colors(bad, "#222222"));
      assertRefused(primary, 400, "VALIDATION_ERROR");
      assertThat(primary.jsonPath().getString("details[0].field")).as("primary %s", bad).isEqualTo("primaryColor");
      Response secondary = refused(administratorOf(A), colors("#111111", bad));
      assertRefused(secondary, 400, "VALIDATION_ERROR");
      assertThat(secondary.jsonPath().getString("details[0].field")).as("secondary %s", bad).isEqualTo("secondaryColor");
    }
    assertRefused(refused(administratorOf(A), Map.of("primaryColor", 5, "secondaryColor", "#222222")), 400, "VALIDATION_ERROR");
    assertRefused(refused(administratorOf(A), Map.of("primaryColor", "#111111")), 400, "VALIDATION_ERROR");
    assertThat(fake.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void C_visual_identity_with_an_undeclared_field_is_400() {
    for (String extra : new String[] {"layout", "logo", "fontFamily", "successColor"}) {
      Map<String, Object> body = colors("#111111", "#222222");
      body.put(extra, "x");
      Response response = refused(administratorOf(A), body);
      assertRefused(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).isEqualTo(extra);
    }
    assertThat(fake.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void C_visual_identity_repeat_returns_200_and_the_same_state() {
    Response first = set(administratorOf(A), colors("#111111", "#222222"));
    Response again = set(administratorOf(A), colors("#111111", "#222222"));
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getMap("visualIdentity")).isEqualTo(first.jsonPath().getMap("visualIdentity"));
    assertThat(again.jsonPath().getString("tenantId")).isEqualTo(first.jsonPath().getString("tenantId"));
  }

  @Test
  void C_visual_identity_of_an_inactive_tenant_is_422() {
    fake.tenants.put(A, fake.tenants.get(A).inactivate("platform-admin", "unpaid", T0.plusSeconds(60)));
    assertRefused(refused(administratorOf(A), colors("#111111", "#222222")), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(fake.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void C_visual_identity_of_a_tenant_that_does_not_exist_is_404() {
    assertRefused(refused(administratorOf("00000000-0000-4000-8000-0000000000ff"), colors("#111111", "#222222")), 404, "NOT_FOUND");
  }

  @Test
  void C_visual_identity_errors_use_the_envelope_with_the_correlation_id_as_trace_id() {
    Response response = administratorOf(A).filter(Contract.answers()).header("X-Correlation-Id", "run-42")
        .body(colors("red", "#222222")).put(ROUTE);
    assertRefused(response, 400, "VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo("run-42");
    assertThat(response.jsonPath().getMap("$").keySet()).isSubsetOf("error", "message", "details", "traceId");
  }

  @Test
  void C_visual_identity_without_token_is_401() {
    Response response = given().filter(Contract.answers()).contentType(ContentType.JSON)
        .body(colors("#111111", "#222222")).put(ROUTE);
    assertRefused(response, 401, "UNAUTHORIZED");
  }

  @Test
  void C_visual_identity_body_over_the_limit_is_413() {
    String head = "{\"primaryColor\":\"";
    String body = head + "a".repeat(16 * 1024 + 1 - head.length() - 2) + "\"}";
    assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(16 * 1024 + 1);
    assertRefused(refused(administratorOf(A), body), 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void C_visual_identity_not_json_is_415() {
    Response response = administratorOf(A).filter(Contract.answers()).contentType("text/plain").body("x").put(ROUTE);
    assertRefused(response, 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void C_public_profile_returns_the_visual_identity() {
    set(administratorOf(A), colors("#146b5d", "#0f564b"));
    Response response = given().filter(Contract.filter()).get(PROFILE + "acme-tours");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
    assertThat(response.jsonPath().getString("visualIdentity.secondaryColor")).isEqualTo("#0F564B");
  }

  @Test
  void C_public_profile_without_one_returns_null() {
    Response response = given().filter(Contract.filter()).get(PROFILE + "acme-tours");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getMap("$")).containsKey("visualIdentity");
    assertThat(response.jsonPath().getMap("$").get("visualIdentity")).isNull();
  }

  @Test
  void C_public_profile_still_reveals_only_four_fields() {
    set(administratorOf(A), colors("#146b5d", "#0f564b"));
    Response response = given().filter(Contract.filter()).get(PROFILE + "acme-tours");
    assertThat(response.jsonPath().getMap("$").keySet())
        .containsExactlyInAnyOrder("identifier", "commercialName", "tenantStatus", "visualIdentity");
    assertThat(response.jsonPath().getMap("visualIdentity").keySet())
        .containsExactlyInAnyOrder("primaryColor", "secondaryColor");
  }
}