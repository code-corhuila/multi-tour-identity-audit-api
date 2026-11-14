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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** POST /internal/tenants/{tenantId}/events: an event of the security microservice becomes an audit record (HU-IDA-002 AC1, AC2, AC7). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoAuditEventRouteTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String ADMIN = "5e5e5e5e-5e5e-4e5e-8e5e-5e5e5e5e5e5e";
  private static final String PATH = "/api/v1/identity-audit/internal/tenants/" + TENANT + "/events";
  private static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("audit_records", "processed_events", "account_session_cutoffs")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification service() {
    String token = Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static Map<String, Object> granted(String eventId) {
    Map<String, Object> body = new HashMap<>();
    body.put("eventId", eventId);
    body.put("eventType", "SpecialPermissionGranted");
    body.put("eventVersion", 1);
    body.put("tenantId", TENANT);
    body.put("aggregateId", ACCOUNT);
    body.put("actorId", ADMIN);
    body.put("reason", "Needs it for the month");
    body.put("payload", Map.of("accountId", ACCOUNT, "permission", "MANAGE_CASH"));
    body.put("occurredAt", AT.toString());
    return body;
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  private static Response deliver(String eventId, Map<String, Object> body) {
    return service().filter(Contract.filter()).header("Idempotency-Key", eventId).body(body).post(PATH);
  }

  private long records(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").countDocuments(new Document("eventId", eventId));
  }

  private Document record(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").find(new Document("eventId", eventId)).first();
  }

  @Test
  void C_a_delivered_event_becomes_one_audit_record_with_its_fields() {
    String id = newId();

    assertThat(deliver(id, granted(id)).statusCode()).isEqualTo(201);

    Document kept = record(id);
    assertThat(kept.getString("tenantId")).isEqualTo(TENANT);
    assertThat(kept.getString("actorId")).isEqualTo(ADMIN);
    assertThat(kept.getString("action")).isEqualTo("SPECIAL_PERMISSION_GRANTED");
    assertThat(kept.getString("affectedRecordId")).isEqualTo("accounts/" + ACCOUNT);
    assertThat(kept.getString("reason")).isEqualTo("Needs it for the month");
    assertThat(kept.get("newValue", Document.class).getString("permission")).isEqualTo("MANAGE_CASH");
    assertThat(kept.getDate("recordedAt").toInstant()).isEqualTo(AT);
    assertThat(kept.getString("_id")).as("its own id, not the id of the event").isNotEqualTo(id);
  }

  @Test
  void C_a_reservation_event_is_recorded_under_reservations_with_its_service_as_the_actor_and_a_person_when_the_event_names_one() {
    String created = newId();
    Map<String, Object> reservation = granted(created);
    reservation.put("eventType", "ReservationCreated");
    reservation.remove("actorId");
    reservation.remove("reason");
    reservation.put("payload", Map.of("customerId", ACCOUNT, "finalValueCents", 120000));
    assertThat(deliver(created, reservation).statusCode()).isEqualTo(201);

    Document kept = record(created);
    assertThat(kept.getString("action")).isEqualTo("RESERVATION_CREATED");
    assertThat(kept.getString("actorId")).isEqualTo("multi-tour-commercial-reservation");
    assertThat(kept.getString("affectedRecordId")).isEqualTo("reservations/" + ACCOUNT);

    String accessed = newId();
    Map<String, Object> sensitive = granted(accessed);
    sensitive.put("eventType", "SensitiveDataAccessed");
    sensitive.remove("actorId");
    sensitive.put("payload", Map.of("customerId", ACCOUNT, "actorId", ADMIN));
    assertThat(deliver(accessed, sensitive).statusCode()).isEqualTo(201);

    Document access = record(accessed);
    assertThat(access.getString("actorId")).isEqualTo(ADMIN);
    assertThat(access.getString("affectedRecordId")).isEqualTo("customers/" + ACCOUNT);
  }

  @Test
  void C_an_event_with_the_extended_fields_keeps_them_and_one_without_an_actor_is_the_security_service() {
    String withFields = newId();
    Map<String, Object> body = granted(withFields);
    body.put("payload", Map.of("previousValue", Map.of("status", "ACTIVE"), "channelOrModule", "ADMIN_PORTAL",
        "functionalProcessReference", "COLLABORATOR_LIFECYCLE", "status", "INACTIVE"));
    assertThat(deliver(withFields, body).statusCode()).isEqualTo(201);

    String noActor = newId();
    Map<String, Object> blocked = granted(noActor);
    blocked.put("eventType", "AccountBlocked");
    blocked.remove("actorId");
    blocked.remove("reason");
    assertThat(deliver(noActor, blocked).statusCode()).isEqualTo(201);

    Document extended = record(withFields);
    assertThat(extended.get("previousValue", Document.class).getString("status")).isEqualTo("ACTIVE");
    assertThat(extended.getString("channelOrModule")).isEqualTo("ADMIN_PORTAL");
    assertThat(extended.getString("functionalProcessReference")).isEqualTo("COLLABORATOR_LIFECYCLE");
    assertThat(record(noActor).getString("actorId")).isEqualTo("multi-tour-security");
    assertThat(record(noActor).containsKey("reason")).isFalse();
  }

  @Test
  void C_a_repeated_event_is_200_and_keeps_one_record() {
    String id = newId();

    assertThat(deliver(id, granted(id)).statusCode()).isEqualTo(201);
    assertThat(deliver(id, granted(id)).statusCode()).isEqualTo(200);
    Map<String, Object> other = granted(id);
    other.put("reason", "Another reason");
    assertThat(deliver(id, other).statusCode()).as("the same event id is a repeat whatever the body says").isEqualTo(200);

    assertThat(records(id)).isEqualTo(1);
    assertThat(record(id).getString("reason")).isEqualTo("Needs it for the month");
  }

  @Test
  void C_an_event_that_cannot_make_a_record_is_400_and_writes_nothing() {
    String id = newId();
    Map<String, Object> notAName = granted(id);
    notAName.put("eventType", "not a name");
    Map<String, Object> noTime = granted(id);
    noTime.remove("occurredAt");
    Map<String, Object> badTime = granted(id);
    badTime.put("occurredAt", "yesterday");
    Map<String, Object> badAggregate = granted(id);
    badAggregate.put("aggregateId", "not-a-uuid");
    Map<String, Object> longActor = granted(id);
    longActor.put("actorId", "a".repeat(101));

    for (Map<String, Object> bad : List.of(notAName, noTime, badTime, badAggregate, longActor)) {
      Response answer = service().filter(Contract.answers()).header("Idempotency-Key", id).body(bad).post(PATH);
      assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(records(id)).isZero();
  }

  @Test
  void C_a_payload_with_a_number_the_database_cannot_hold_is_400_not_a_failure() {
    String id = newId();
    Map<String, Object> body = granted(id);
    body.put("payload", Map.of("accountId", ACCOUNT, "amount", new java.math.BigInteger("1" + "0".repeat(30))));

    Response answer = service().filter(Contract.answers()).header("Idempotency-Key", id).body(body).post(PATH);

    assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
    assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    assertThat(records(id)).isZero();
  }

  @Test
  void I_two_deliveries_at_once_of_the_same_event_keep_one_record() throws Exception {
    String id = newId();
    int deliveries = 8;
    ExecutorService pool = Executors.newFixedThreadPool(deliveries);
    CyclicBarrier together = new CyclicBarrier(deliveries);
    try {
      Callable<Integer> delivery = () -> {
        together.await();   // nobody sends until everybody is ready, so the requests really overlap
        return service().header("Idempotency-Key", id).body(granted(id)).post(PATH).statusCode();
      };
      List<Future<Integer>> answers = new java.util.ArrayList<>();
      for (int n = 0; n < deliveries; n++) {
        answers.add(pool.submit(delivery));
      }
      List<Integer> statuses = new java.util.ArrayList<>();
      for (Future<Integer> answer : answers) {
        statuses.add(answer.get());
      }

      assertThat(statuses).as("one is recorded and the others are repeats").containsOnly(201, 200).containsOnlyOnce(201);
    } finally {
      pool.shutdownNow();
    }
    assertThat(records(id)).isEqualTo(1);
  }
}
