package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Date;
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

/** POST /internal/tenants/{tenantId}/events: a session revocation of the security microservice, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoSessionEventRouteTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String PATH = "/api/v1/identity-audit/internal/tenants/" + TENANT + "/events";

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys", "processed_events", "account_session_cutoffs")) {
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

  private static Map<String, Object> event(String eventId, Instant revokedFrom) {
    Map<String, Object> body = new HashMap<>();
    body.put("eventId", eventId);
    body.put("eventType", "AccountSessionsRevoked");
    body.put("eventVersion", 1);
    body.put("tenantId", TENANT);
    body.put("aggregateId", ACCOUNT);
    body.put("payload", Map.of("accountId", ACCOUNT, "revokedFrom", revokedFrom.toString()));
    body.put("occurredAt", revokedFrom.toString());
    return body;
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  private static Response deliver(String eventId, Map<String, Object> body) {
    return service().filter(Contract.filter()).header("Idempotency-Key", eventId).body(body).post(PATH);
  }

  private static Response refused(RequestSpecification spec, Object body, String key) {
    RequestSpecification withKey = key == null ? spec : spec.header("Idempotency-Key", key);
    return withKey.filter(Contract.answers()).body(body).post(PATH);
  }

  private Document cutoff() {
    return client.getDatabase(database).getCollection("account_session_cutoffs").find(new Document("_id", ACCOUNT)).first();
  }

  @Test
  void C_receiving_route_answers_401_without_token_and_403_without_the_service_role() {
    String id = newId();
    Map<String, Object> body = event(id, Instant.parse("2026-10-24T10:00:00Z"));

    assertThat(given().contentType(ContentType.JSON).filter(Contract.answers()).header("Idempotency-Key", id).body(body)
        .post(PATH).statusCode()).isEqualTo(401);
    for (String role : List.of("PLATFORM_ADMINISTRATOR", "TENANT_ADMINISTRATOR", "FINAL_CUSTOMER")) {
      assertThat(refused(as(List.of(role)), body, id).statusCode()).as(role).isEqualTo(403);
    }
    assertThat(cutoff()).as("a refused caller writes nothing").isNull();
  }

  @Test
  void C_first_delivery_is_201_and_a_repeat_is_200() {
    String id = newId();
    Map<String, Object> body = event(id, Instant.parse("2026-10-24T10:00:00Z"));

    assertThat(deliver(id, body).statusCode()).isEqualTo(201);
    assertThat(deliver(id, body).statusCode()).isEqualTo(200);
    // the same event id again is a repeat whatever the body says now (as the other creations of the service)
    Map<String, Object> other = event(id, Instant.parse("2026-10-24T18:00:00Z"));
    assertThat(deliver(id, other).statusCode()).isEqualTo(200);
    assertThat(cutoff().getDate("revokedFrom").toInstant()).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
  }

  @Test
  void C_receiving_route_validates_the_body_as_the_contract_says() {
    String id = newId();
    Instant at = Instant.parse("2026-10-24T10:00:00Z");
    Map<String, Object> noPayload = event(id, at);
    noPayload.remove("payload");
    Map<String, Object> wrongType = event(id, at);
    wrongType.put("eventType", "TenantInactivated");
    Map<String, Object> badAccount = event(id, at);
    badAccount.put("payload", Map.of("accountId", "not-a-uuid", "revokedFrom", at.toString()));
    Map<String, Object> badDate = event(id, at);
    badDate.put("payload", Map.of("accountId", ACCOUNT, "revokedFrom", "yesterday"));
    Map<String, Object> otherTenant = event(id, at);
    otherTenant.put("tenantId", "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a");
    Map<String, Object> otherAggregate = event(id, at);
    otherAggregate.put("aggregateId", "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b");

    for (Map<String, Object> bad : List.of(noPayload, wrongType, badAccount, badDate, otherTenant, otherAggregate)) {
      Response answer = refused(service(), bad, id);
      assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(refused(service(), event(id, at), newId()).statusCode()).as("the key is the id of the event").isEqualTo(400);
    assertThat(refused(service(), event(id, at), null).statusCode()).as("the key is required").isEqualTo(400);
    assertThat(refused(service(), "{\"eventId\": ", id).statusCode()).as("malformed JSON").isEqualTo(400);
    Map<String, Object> big = event(id, at);
    big.put("padding", "x".repeat(17 * 1024));
    assertThat(refused(service(), big, id).statusCode()).as("more than 16 KB").isEqualTo(413);
    assertThat(service().contentType(ContentType.TEXT).header("Idempotency-Key", id).body("plain").post(PATH).statusCode())
        .isEqualTo(415);
    Map<String, Object> tolerant = event(id, at);
    tolerant.put("correlationId", "corr-1");
    tolerant.put("somethingNew", true);
    assertThat(deliver(id, tolerant).statusCode()).as("fields it does not name are tolerated").isEqualTo(201);
    assertThat(cutoff()).as("a refused request writes nothing before this one").isNotNull();
  }

  @Test
  void I_cutoff_and_processed_id_are_written_together() {
    String id = newId();
    Instant at = Instant.parse("2026-10-24T10:00:00Z");

    assertThat(deliver(id, event(id, at)).statusCode()).isEqualTo(201);

    Document processed = client.getDatabase(database).getCollection("processed_events").find(new Document("_id", id)).first();
    Document cutoff = cutoff();
    assertThat(processed).isNotNull();
    assertThat(processed.getDate("processedAt")).isNotNull();
    assertThat(cutoff.getString("tenantId")).isEqualTo(TENANT);
    assertThat(cutoff.getDate("revokedFrom")).isEqualTo(Date.from(at));
    assertThat(cutoff.getDate("updatedAt")).isNotNull();
    Date updatedAt = cutoff.getDate("updatedAt");
    deliver(id, event(id, at));
    assertThat(cutoff().getDate("updatedAt")).as("a repeat rewrites nothing").isEqualTo(updatedAt);
  }

  @Test
  void I_an_older_event_leaves_the_newer_cutoff() {
    Instant t10 = Instant.parse("2026-10-24T10:00:00Z");
    Instant t20 = Instant.parse("2026-10-24T20:00:00Z");
    Instant t30 = Instant.parse("2026-10-24T21:30:00Z");
    String first = newId();
    String older = newId();
    String newer = newId();

    assertThat(deliver(first, event(first, t20)).statusCode()).isEqualTo(201);
    assertThat(deliver(older, event(older, t10)).statusCode()).as("an older event is not an error").isEqualTo(200);
    assertThat(cutoff().getDate("revokedFrom").toInstant()).isEqualTo(t20);
    assertThat(client.getDatabase(database).getCollection("processed_events").countDocuments(new Document("_id", older)))
        .as("the older event is remembered, so a repeat of it is also a repeat").isEqualTo(1);

    assertThat(deliver(newer, event(newer, t30)).statusCode()).isEqualTo(201);
    assertThat(cutoff().getDate("revokedFrom").toInstant()).isEqualTo(t30);
  }
}
