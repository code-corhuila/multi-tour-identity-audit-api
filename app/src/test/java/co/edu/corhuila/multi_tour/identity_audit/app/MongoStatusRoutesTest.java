package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** POST /tenants/{tenantId}/inactivate and /reactivate for the Platform Administrator, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoStatusRoutesTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";
  private static final String UNKNOWN = "11111111-2222-4333-8444-555555555555";

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification service() {
    String token = Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification admin() {
    return given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
  }

  private static String pending(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO");
    return service().header("Idempotency-Key", "onboard:" + identifier).body(body).post(INTERNAL)
        .jsonPath().getString("tenantId");
  }

  private static String active(String identifier) {
    String id = pending(identifier);
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + id + "/complete-onboarding").then().statusCode(200);
    return id;
  }

  private static Response inactivate(String id, Object body) {
    return admin().filter(Contract.filter()).body(body).post(TENANTS + "/" + id + "/inactivate");
  }

  private static Response reactivate(String id, Object body) {
    return admin().filter(Contract.filter()).body(body).post(TENANTS + "/" + id + "/reactivate");
  }

  private static Response refused(String path, Object body) {
    return admin().filter(Contract.answers()).body(body).post(TENANTS + "/" + path);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  private long events(String type) {
    return client.getDatabase(database).getCollection("outbox_events").countDocuments(new Document("eventType", type));
  }

  @Test
  void C_inactivating_an_active_tenant_keeps_who_and_why_and_writes_one_event_with_the_correlation_id() {
    String id = active("acme-tours");
    Response response = admin().filter(Contract.filter()).header("X-Correlation-Id", "abc-123")
        .body(Map.of("reason", "  Contract expired ")).post(TENANTS + "/" + id + "/inactivate");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("INACTIVE");
    assertThat(response.jsonPath().getString("statusReason")).isEqualTo("Contract expired");
    assertThat(response.jsonPath().getString("statusChangedBy")).isEqualTo("account-1");
    Document event = client.getDatabase(database).getCollection("outbox_events")
        .find(new Document("eventType", "TenantInactivated")).first();
    assertThat(event.getString("correlationId")).isEqualTo("abc-123");
    assertThat(event.getString("actorId")).isEqualTo("account-1");
  }

  @Test
  void C_reactivating_an_inactive_tenant_makes_it_active_again_and_writes_its_event() {
    String id = active("acme-tours");
    inactivate(id, Map.of("reason", "Contract expired"));
    Response response = reactivate(id, Map.of("reason", "Paid"));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
    assertThat(response.jsonPath().getString("statusReason")).isEqualTo("Paid");
    assertThat(events("TenantInactivated")).isEqualTo(1);
    assertThat(events("TenantReactivated")).isEqualTo(1);
  }

  @Test
  void C_a_repeat_or_a_tenant_in_another_status_is_a_422_invalid_status_transition_and_the_first_reason_stays() {
    String id = active("acme-tours");
    inactivate(id, Map.of("reason", "First"));
    assertRefused(refused(id + "/inactivate", Map.of("reason", "Second")), 422, "INVALID_STATUS_TRANSITION");
    assertThat(admin().get(TENANTS + "/" + id).jsonPath().getString("statusReason")).isEqualTo("First");
    reactivate(id, Map.of("reason", "Paid"));
    assertRefused(refused(id + "/reactivate", Map.of("reason", "Again")), 422, "INVALID_STATUS_TRANSITION");
    assertRefused(refused(pending("pending-tours") + "/inactivate", Map.of("reason", "x")), 422, "INVALID_STATUS_TRANSITION");
    assertRefused(refused(pending("other-tours") + "/reactivate", Map.of("reason", "x")), 422, "INVALID_STATUS_TRANSITION");
    assertThat(events("TenantInactivated")).isEqualTo(1);
    assertThat(events("TenantReactivated")).isEqualTo(1);
  }

  @Test
  void C_a_reason_that_is_missing_null_blank_too_long_or_not_a_text_and_a_field_not_declared_are_a_400() {
    String id = active("acme-tours");
    Map<String, Object> nulled = new HashMap<>();
    nulled.put("reason", null);
    for (String route : List.of("inactivate", "reactivate")) {
      for (Object bad : List.of(Map.of(), nulled, Map.of("reason", "  "), Map.of("reason", "r".repeat(501)),
          Map.of("reason", 5), Map.of("reason", "ok", "extra", 1), "{not json", "", "null", "[]")) {
        assertRefused(refused(id + "/" + route, bad), 400, "VALIDATION_ERROR");
      }
      assertRefused(refused("not-a-uuid/" + route, Map.of("reason", "ok")), 400, "VALIDATION_ERROR");
    }
    assertThat(inactivate(id, Map.of("reason", "r".repeat(500))).statusCode()).isEqualTo(200);
  }

  @Test
  void C_a_tenant_that_does_not_exist_is_a_404_with_the_fixed_message_on_both_routes() {
    for (String route : List.of("inactivate", "reactivate")) {
      Response response = refused(UNKNOWN + "/" + route, Map.of("reason", "ok"));
      assertRefused(response, 404, "NOT_FOUND");
      assertThat(response.jsonPath().getString("message")).isEqualTo("There is no such tenant");
    }
  }

  @Test
  void C_without_a_token_a_401_and_with_a_service_token_a_403_judged_before_the_body_on_both_routes() {
    String id = active("acme-tours");
    for (String route : List.of("inactivate", "reactivate")) {
      String path = TENANTS + "/" + id + "/" + route;
      assertRefused(given().filter(Contract.answers()).contentType(ContentType.JSON).body(Map.of("reason", "ok")).post(path),
          401, "UNAUTHORIZED");
      assertRefused(service().filter(Contract.answers()).body(Map.of("reason", "ok")).post(path), 403, "FORBIDDEN");
      assertRefused(service().filter(Contract.answers()).body("{not json").post(path), 403, "FORBIDDEN");
    }
    assertThat(admin().get(TENANTS + "/" + id).jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
  }

  @Test
  void C_a_body_that_is_not_json_is_a_415() {
    String id = active("acme-tours");
    Response response = admin().filter(Contract.answers()).contentType("text/plain").body("x")
        .post(TENANTS + "/" + id + "/inactivate");
    assertRefused(response, 415, "UNSUPPORTED_MEDIA_TYPE");
  }
}
