package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.nimbusds.jwt.JWTClaimsSet;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * An account of the Platform Administrator belongs to no tenant (ADR-004 D4): its revocation keeps a cutoff and a record with
 * no tenant, a token before the cutoff is refused, and the record is read across tenants only. Against the real database.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoPlatformAccountTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String OTHER_TENANT = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String ACCOUNT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String EVENTS = "/api/v1/identity-audit/internal/events";
  private static final String TENANT_EVENTS = "/api/v1/identity-audit/internal/tenants/" + TENANT + "/events";
  private static final String ALL = "/api/v1/identity-audit/audit-records";
  /** A protected route that is not built, so passing the filter shows as the 404 of an unknown route. */
  private static final String PROTECTED = "/api/v1/identity-audit/not-built";
  private static final Instant T10 = Instant.parse("2026-11-07T10:00:00Z");
  private static final Instant T20 = Instant.parse("2026-11-07T20:00:00Z");
  private static final Instant R = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("audit_records", "processed_events", "account_session_cutoffs", "audit_access_settings")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification service() {
    String token = Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  /** A revocation of the sessions of {@code ACCOUNT}; with {@code tenantId} null it is an account of the platform. */
  private static Map<String, Object> revocation(String eventId, String tenantId, Instant from) {
    Map<String, Object> body = new HashMap<>();
    body.put("eventId", eventId);
    body.put("eventType", "AccountSessionsRevoked");
    body.put("eventVersion", 1);
    if (tenantId != null) {
      body.put("tenantId", tenantId);
    }
    body.put("aggregateId", ACCOUNT);
    body.put("payload", Map.of("accountId", ACCOUNT, "revokedFrom", from.toString()));
    body.put("occurredAt", from.toString());
    return body;
  }

  private static Response post(String path, String eventId, Map<String, Object> body) {
    return service().filter(Contract.answers()).header("Idempotency-Key", eventId).body(body).post(path);
  }

  private Document cutoff() {
    return client.getDatabase(database).getCollection("account_session_cutoffs").find(new Document("_id", ACCOUNT)).first();
  }

  private long records(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").countDocuments(new Document("eventId", eventId));
  }

  /** The id the audit routes show for the record of an event; they do not show the id of the event. */
  private String auditRecordId(String eventId) {
    return client.getDatabase(database).getCollection("audit_records").find(new Document("eventId", eventId)).first().getString("_id");
  }

  private long processed(String eventId) {
    return client.getDatabase(database).getCollection("processed_events").countDocuments(new Document("_id", eventId));
  }

  @Test
  void I_a_platform_account_revocation_keeps_its_cutoff_and_its_record_together() {
    String id = newId();
    String older = newId();

    assertThat(post(EVENTS, id, revocation(id, null, T20)).statusCode()).isEqualTo(201);

    Document kept = cutoff();
    assertThat(kept.containsKey("tenantId")).as("the cutoff of a platform account has no tenant").isFalse();
    assertThat(kept.getDate("revokedFrom")).isEqualTo(Date.from(T20));
    Document record = client.getDatabase(database).getCollection("audit_records").find(new Document("eventId", id)).first();
    assertThat(record.containsKey("tenantId")).as("and neither has its record").isFalse();
    assertThat(record.getString("action")).isEqualTo("ACCOUNT_SESSIONS_REVOKED");
    assertThat(record.getString("affectedRecordId")).isEqualTo("accounts/" + ACCOUNT);
    assertThat(processed(id)).isEqualTo(1);

    assertThat(post(EVENTS, id, revocation(id, null, T20)).statusCode()).as("a repeat").isEqualTo(200);
    assertThat(post(EVENTS, older, revocation(older, null, T10)).statusCode()).as("an older event").isEqualTo(200);
    assertThat(records(id)).as("a repeat keeps one record").isEqualTo(1);
    assertThat(records(older)).as("an older event still leaves its record").isEqualTo(1);
    assertThat(cutoff().getDate("revokedFrom")).as("and does not move the cutoff").isEqualTo(Date.from(T20));
  }

  @Test
  void I_a_platform_revocation_whose_record_cannot_be_kept_leaves_no_cutoff_and_no_processed_id() {
    String id = newId();
    Map<String, Object> body = revocation(id, null, T10);
    body.put("payload", Map.of("accountId", ACCOUNT, "revokedFrom", T10.toString(),
        "amount", new java.math.BigInteger("1" + "0".repeat(30))));

    assertThat(post(EVENTS, id, body).statusCode()).isEqualTo(400);

    assertThat(cutoff()).as("both or neither").isNull();
    assertThat(processed(id)).isZero();
    assertThat(records(id)).isZero();
  }

  @Test
  void I_an_account_keeps_to_one_tenant_or_to_none_on_both_routes() {
    String platform = newId();
    String forTenant = newId();

    assertThat(post(EVENTS, platform, revocation(platform, null, T10)).statusCode()).isEqualTo(201);
    Response withTenant = post(TENANT_EVENTS, forTenant, revocation(forTenant, TENANT, T20));
    assertThat(withTenant.statusCode()).as("an account with no tenant refuses an event with one: %s", withTenant.asString()).isEqualTo(400);
    assertThat(withTenant.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    assertThat(cutoff().containsKey("tenantId")).isFalse();
    assertThat(cutoff().getDate("revokedFrom")).isEqualTo(Date.from(T10));
    assertThat(records(forTenant) + processed(forTenant)).as("and writes nothing").isZero();

    client.getDatabase(database).getCollection("account_session_cutoffs").deleteMany(new Document());
    String ofTenant = newId();
    String noTenant = newId();
    assertThat(post(TENANT_EVENTS, ofTenant, revocation(ofTenant, TENANT, T10)).statusCode()).isEqualTo(201);
    Response withNone = post(EVENTS, noTenant, revocation(noTenant, null, T20));
    assertThat(withNone.statusCode()).as("an account of a tenant refuses an event with none: %s", withNone.asString()).isEqualTo(400);
    assertThat(withNone.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    assertThat(cutoff().getString("tenantId")).isEqualTo(TENANT);
    assertThat(cutoff().getDate("revokedFrom")).isEqualTo(Date.from(T10));
    assertThat(records(noTenant) + processed(noTenant)).as("and writes nothing").isZero();
  }

  private static JWTClaimsSet.Builder platformAdmin(Instant issuedAt) {
    return Tokens.claims(ACCOUNT, 600, List.of("PLATFORM_ADMINISTRATOR"), null).issueTime(Date.from(issuedAt));
  }

  private static Response call(JWTClaimsSet.Builder claims) {
    return given().header("Authorization", "Bearer " + Tokens.valid(claims)).get(PROTECTED);
  }

  @Test
  void C_platform_token_issued_before_the_cutoff_is_401_and_a_later_one_passes() {
    String id = newId();
    assertThat(post(EVENTS, id, revocation(id, null, R)).statusCode()).isEqualTo(201);

    Response before = call(platformAdmin(R.minusSeconds(60)));
    assertThat(before.statusCode()).isEqualTo(401);
    assertThat(before.jsonPath().getString("error")).isEqualTo("UNAUTHORIZED");

    assertThat(call(platformAdmin(R.plusSeconds(60))).statusCode()).as("issued after the cutoff").isEqualTo(404);
    assertThat(call(platformAdmin(R)).statusCode()).as("issued in the second of the cutoff, as a new sign-in is").isEqualTo(404);
  }

  @Test
  void C_a_record_with_no_tenant_is_read_across_tenants_and_never_by_a_tenant() {
    String id = newId();
    Map<String, Object> blocked = revocation(id, null, T10);
    blocked.put("eventType", "AccountBlocked");
    blocked.put("payload", Map.of("accountId", ACCOUNT, "status", "BLOCKED"));
    assertThat(post(EVENTS, id, blocked).statusCode()).isEqualTo(201);
    String tenantEvent = newId();
    Map<String, Object> ofTenant = revocation(tenantEvent, TENANT, T10);
    ofTenant.put("eventType", "AccountBlocked");
    ofTenant.put("payload", Map.of("accountId", ACCOUNT, "status", "BLOCKED"));
    assertThat(post(TENANT_EVENTS, tenantEvent, ofTenant).statusCode()).isEqualTo(201);
    String platformRecord = auditRecordId(id);
    String tenantRecord = auditRecordId(tenantEvent);

    RequestSpecification platform = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin()));
    Response everything = platform.filter(Contract.filter()).get(ALL);
    assertThat(everything.statusCode()).isEqualTo(200);
    assertThat(everything.jsonPath().getList("data.auditRecordId")).as("across tenants the platform's record is there")
        .contains(platformRecord, tenantRecord);
    List<Map<String, Object>> data = everything.jsonPath().getList("data");
    assertThat(data.stream().filter(r -> platformRecord.equals(r.get("auditRecordId"))).findFirst().orElseThrow().get("tenantId")).isNull();

    Response filtered = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).filter(Contract.filter())
        .get(ALL + "?tenantId=" + TENANT);
    assertThat(filtered.jsonPath().getList("data.auditRecordId")).as("the filter by tenant never returns it").containsExactly(tenantRecord);

    for (String tenant : List.of(TENANT, OTHER_TENANT)) {
      String token = Tokens.valid(Tokens.claims(newId(), 600, List.of("TENANT_ADMINISTRATOR"), tenant));
      for (String route : List.of("/api/v1/identity-audit/tenants/" + tenant + "/audit-records", "/api/v1/identity-audit/tenant/audit-records")) {
        Response own = given().header("Authorization", "Bearer " + token).filter(Contract.answers()).get(route);
        assertThat(own.statusCode()).as(route).isEqualTo(200);
        assertThat(own.jsonPath().getList("data.auditRecordId")).as("the audit of %s on %s", tenant, route).doesNotContain(platformRecord);
        if (tenant.equals(TENANT)) {
          assertThat(own.jsonPath().getList("data.auditRecordId")).as("it does answer with the records of its own tenant").contains(tenantRecord);
        }
      }
    }
  }
}
