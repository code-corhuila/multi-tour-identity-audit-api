package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** POST /internal/outbox/{eventId}/attempts: a failed delivery, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoOutboxAttemptsRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String OUTBOX = "/api/v1/identity-audit/internal/outbox";
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
    String token = Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  /** A tenant that finished its onboarding leaves one event TenantCreated waiting; its id. */
  private static String waitingEvent() {
    Map<String, Object> body = Map.of(
        "identifier", "acme-tours", "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO");
    String id = service().header("Idempotency-Key", "onboard:acme").body(body).post(INTERNAL).jsonPath().getString("tenantId");
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + id + "/complete-onboarding").then().statusCode(200);
    return service().get(OUTBOX).jsonPath().getString("data[0].eventId");
  }

  private static Response attempt(String id, String error) {
    return service().filter(Contract.filter()).body(Map.of("error", error)).post(OUTBOX + "/" + id + "/attempts");
  }

  private static Response refused(String id, Object body) {
    return service().filter(Contract.answers()).body(body).post(OUTBOX + "/" + id + "/attempts");
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  private static Duration waitOf(Response response) {
    return Duration.between(Instant.now(), Instant.parse(response.jsonPath().getString("nextAttemptAt")));
  }

  @Test
  void C_a_failed_delivery_counts_keeps_the_error_and_waits_double_and_the_event_leaves_the_list_until_due() {
    String id = waitingEvent();
    Response first = attempt(id, "  consumer answered 503 ");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.jsonPath().getInt("attempts")).isEqualTo(1);
    assertThat(first.jsonPath().getString("lastError")).isEqualTo("consumer answered 503");
    assertThat(first.jsonPath().getString("status")).isEqualTo("PENDING");
    assertThat(waitOf(first)).isBetween(Duration.ZERO, Duration.ofSeconds(6));
    assertThat(service().get(OUTBOX).jsonPath().getList("data")).isEmpty();
    Response second = attempt(id, "again");
    assertThat(second.jsonPath().getInt("attempts")).isEqualTo(2);
    // the wait doubled: 10 s after the second report against 5 s after the first
    assertThat(Duration.between(Instant.parse(first.jsonPath().getString("nextAttemptAt")),
        Instant.parse(second.jsonPath().getString("nextAttemptAt")))).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(9));
    client.getDatabase(database).getCollection("outbox_events")
        .updateOne(new Document(), new Document("$set", new Document("nextAttemptAt", java.util.Date.from(Instant.now().minusSeconds(1)))));
    assertThat(service().get(OUTBOX).jsonPath().getList("data")).hasSize(1);
  }

  @Test
  void C_the_last_of_20_attempts_marks_the_event_failed_and_it_stays_out_of_the_list_but_can_still_be_published() {
    String id = waitingEvent();
    Response last = null;
    for (int i = 0; i < 20; i++) {
      last = attempt(id, "down");
    }
    assertThat(last.jsonPath().getString("status")).isEqualTo("FAILED");
    assertThat(last.jsonPath().getInt("attempts")).isEqualTo(20);
    assertThat(attempt(id, "late").jsonPath().getInt("attempts")).isEqualTo(20);
    assertThat(service().get(OUTBOX).jsonPath().getList("data")).isEmpty();
    Response published = service().filter(Contract.filter()).post(OUTBOX + "/" + id + "/published");
    assertThat(published.jsonPath().getString("status")).isEqualTo("PUBLISHED");
    assertThat(published.jsonPath().getString("lastError")).isEqualTo("down");
  }

  @Test
  void C_a_report_that_comes_after_the_event_was_published_changes_nothing() {
    String id = waitingEvent();
    service().post(OUTBOX + "/" + id + "/published").then().statusCode(200);
    Response late = attempt(id, "late");
    assertThat(late.jsonPath().getString("status")).isEqualTo("PUBLISHED");
    assertThat(late.jsonPath().getInt("attempts")).isZero();
  }

  @Test
  void C_an_error_that_is_missing_null_blank_too_long_or_not_a_text_and_a_field_not_declared_are_a_400() {
    String id = waitingEvent();
    Map<String, Object> nulled = new HashMap<>();
    nulled.put("error", null);
    for (Object bad : List.of(Map.of(), nulled, Map.of("error", "  "), Map.of("error", "e".repeat(1001)), Map.of("error", 5),
        Map.of("error", "ok", "extra", 1), "{\"error\":\"a\\nb\"}", "{\"error\":\"a\",\"error\":\"b\"}", "{\"error\":[\"a\"]}", "{\"error\":{}}",
        "{not json", "", "null", "[]")) {
      assertRefused(refused(id, bad), 400, "VALIDATION_ERROR");
    }
    assertRefused(refused("not-a-uuid", Map.of("error", "ok")), 400, "VALIDATION_ERROR");
    assertThat(attempt(id, "e".repeat(1000)).statusCode()).isEqualTo(200);
  }

  @Test
  void C_an_event_that_does_not_exist_is_a_404_with_the_fixed_message() {
    Response missing = refused(UNKNOWN, Map.of("error", "down"));
    assertRefused(missing, 404, "NOT_FOUND");
    assertThat(missing.jsonPath().getString("message")).isEqualTo("There is no such event");
  }

  @Test
  void C_without_a_token_a_401_with_a_person_token_a_403_judged_before_the_body_and_a_text_body_is_a_415() {
    String id = waitingEvent();
    String path = OUTBOX + "/" + id + "/attempts";
    assertRefused(given().filter(Contract.answers()).contentType(ContentType.JSON).body(Map.of("error", "x")).post(path), 401, "UNAUTHORIZED");
    RequestSpecification person = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
    assertRefused(person.filter(Contract.answers()).body(Map.of("error", "x")).post(path), 403, "FORBIDDEN");
    assertRefused(person.filter(Contract.answers()).body("{not json").post(path), 403, "FORBIDDEN");
    assertRefused(service().filter(Contract.answers()).contentType("text/plain").body("x").post(path), 415, "UNSUPPORTED_MEDIA_TYPE");
    assertThat(service().get(OUTBOX).jsonPath().getList("data")).hasSize(1);
  }
}
