package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** POST /internal/events: an event of a Platform Administrator's account, which belongs to no tenant (ADR-004 D4), against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoPlatformEventRouteTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String PATH = "/api/v1/identity-audit/internal/events";
  private static final String TENANT_PATH = "/api/v1/identity-audit/internal/tenants/" + TENANT + "/events";
  private static final Instant AT = Instant.parse("2026-11-07T10:00:00Z");

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("audit_records", "processed_events", "account_session_cutoffs")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification as(List<String> roles) {
    String token = Tokens.valid(Tokens.claims("event-relay", 600, roles, null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification service() {
    return as(List.of("SERVICE"));
  }

  /** An account of the Platform Administrator is blocked: the event has no tenant. */
  private static Map<String, Object> blocked(String eventId) {
    Map<String, Object> body = new HashMap<>();
    body.put("eventId", eventId);
    body.put("eventType", "AccountBlocked");
    body.put("eventVersion", 1);
    body.put("aggregateId", ACCOUNT);
    body.put("reason", "Too many failed sign-ins");
    body.put("payload", Map.of("accountId", ACCOUNT, "status", "BLOCKED"));
    body.put("occurredAt", AT.toString());
    return body;
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  private static Response deliver(String eventId, Map<String, Object> body) {
    return service().filter(Contract.filter()).header("Idempotency-Key", eventId).body(body).post(PATH);
  }

  private static Response refused(RequestSpecification spec, Object body, String key, String path) {
    RequestSpecification withKey = key == null ? spec : spec.header("Idempotency-Key", key);
    return withKey.filter(Contract.answers()).body(body).post(path);
  }

  private long records(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").countDocuments(new Document("eventId", eventId));
  }

  private Document record(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").find(new Document("eventId", eventId)).first();
  }

  @Test
  void C_platform_event_route_answers_401_without_token_and_403_without_the_service_role() {
    String id = newId();
    Map<String, Object> body = blocked(id);

    assertThat(given().contentType(ContentType.JSON).filter(Contract.answers()).header("Idempotency-Key", id).body(body)
        .post(PATH).statusCode()).isEqualTo(401);
    for (String role : List.of("PLATFORM_ADMINISTRATOR", "TENANT_ADMINISTRATOR", "FINAL_CUSTOMER")) {
      assertThat(refused(as(List.of(role)), body, id, PATH).statusCode()).as(role).isEqualTo(403);
    }
    assertThat(records(id)).as("a refused caller writes nothing").isZero();
  }

  @Test
  void C_each_route_refuses_the_envelope_of_the_other() {
    String id = newId();
    Map<String, Object> withTenant = blocked(id);
    withTenant.put("tenantId", TENANT);
    Map<String, Object> withNoTenant = blocked(id);

    Response onPlatformRoute = refused(service(), withTenant, id, PATH);
    Response onTenantRoute = refused(service(), withNoTenant, id, TENANT_PATH);

    assertThat(onPlatformRoute.statusCode()).as("an event with a tenant on the route without tenant: %s", onPlatformRoute.asString()).isEqualTo(400);
    assertThat(onPlatformRoute.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    assertThat(onTenantRoute.statusCode()).as("an event with no tenant on the route of a tenant: %s", onTenantRoute.asString()).isEqualTo(400);
    assertThat(onTenantRoute.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    // a tenant that is there but empty, blank or in capitals is still a tenant on the route without one, and nothing on the route of a tenant
    for (String odd : List.of("", " ", TENANT.toUpperCase())) {
      Map<String, Object> oddTenant = blocked(id);
      oddTenant.put("tenantId", odd);
      assertThat(refused(service(), oddTenant, id, PATH).statusCode()).as("tenantId '%s' on the route without tenant", odd).isEqualTo(400);
      assertThat(refused(service(), oddTenant, id, TENANT_PATH).statusCode()).as("tenantId '%s' on the route of a tenant", odd).isEqualTo(400);
    }
    Map<String, Object> nullTenant = blocked(id);
    nullTenant.put("tenantId", null);
    assertThat(refused(service(), nullTenant, id, TENANT_PATH).statusCode()).as("tenantId null on the route of a tenant").isEqualTo(400);
    assertThat(records(id)).as("neither route kept anything").isZero();
    assertThat(deliver(id, withNoTenant).statusCode()).as("and the event is accepted where it belongs").isEqualTo(201);
  }

  @Test
  void C_an_event_with_no_tenant_becomes_one_record_with_no_tenant() {
    String id = newId();

    assertThat(deliver(id, blocked(id)).statusCode()).isEqualTo(201);

    Document kept = record(id);
    assertThat(kept.containsKey("tenantId")).as("the record belongs to no tenant").isFalse();
    assertThat(kept.getString("action")).isEqualTo("ACCOUNT_BLOCKED");
    assertThat(kept.getString("affectedRecordId")).isEqualTo("accounts/" + ACCOUNT);
    assertThat(kept.getString("actorId")).isEqualTo("multi-tour-security");
    assertThat(kept.getString("reason")).isEqualTo("Too many failed sign-ins");
    assertThat(kept.getDate("recordedAt").toInstant()).isEqualTo(AT);
  }

  @Test
  void C_a_repeated_event_with_no_tenant_is_200_and_keeps_one_record() {
    String id = newId();

    assertThat(deliver(id, blocked(id)).statusCode()).isEqualTo(201);
    assertThat(deliver(id, blocked(id)).statusCode()).isEqualTo(200);
    Map<String, Object> other = blocked(id);
    other.put("reason", "Another reason");
    assertThat(deliver(id, other).statusCode()).as("the same event id is a repeat whatever the body says").isEqualTo(200);

    assertThat(records(id)).isEqualTo(1);
    assertThat(record(id).getString("reason")).isEqualTo("Too many failed sign-ins");
  }

  @Test
  void C_platform_event_route_validates_the_body_as_the_contract_says() {
    String id = newId();
    Map<String, Object> noPayload = blocked(id);
    noPayload.remove("payload");
    Map<String, Object> notAName = blocked(id);
    notAName.put("eventType", "not a name");
    Map<String, Object> badAggregate = blocked(id);
    badAggregate.put("aggregateId", "not-a-uuid");
    Map<String, Object> noTime = blocked(id);
    noTime.remove("occurredAt");
    Map<String, Object> badTime = blocked(id);
    badTime.put("occurredAt", "yesterday");
    Map<String, Object> badRevocation = blocked(id);
    badRevocation.put("eventType", "AccountSessionsRevoked");
    badRevocation.put("payload", Map.of("accountId", "not-a-uuid", "revokedFrom", AT.toString()));

    for (Map<String, Object> bad : List.of(noPayload, notAName, badAggregate, noTime, badTime, badRevocation)) {
      Response answer = refused(service(), bad, id, PATH);
      assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(refused(service(), blocked(id), newId(), PATH).statusCode()).as("the key is the id of the event").isEqualTo(400);
    assertThat(refused(service(), blocked(id), null, PATH).statusCode()).as("the key is required").isEqualTo(400);
    assertThat(refused(service(), "{\"eventId\": ", id, PATH).statusCode()).as("malformed JSON").isEqualTo(400);
    Map<String, Object> big = blocked(id);
    big.put("padding", "x".repeat(17 * 1024));
    assertThat(refused(service(), big, id, PATH).statusCode()).as("more than 16 KB").isEqualTo(413);
    assertThat(service().contentType(ContentType.TEXT).header("Idempotency-Key", id).body("plain").post(PATH).statusCode())
        .isEqualTo(415);
    assertThat(records(id)).as("a refused request writes nothing").isZero();
    Map<String, Object> tolerant = blocked(id);
    tolerant.put("correlationId", "corr-1");
    tolerant.put("somethingNew", true);
    assertThat(deliver(id, tolerant).statusCode()).as("fields it does not name are tolerated").isEqualTo(201);
  }
}
