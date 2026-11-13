package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.nimbusds.jwt.JWTClaimsSet;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** A token issued before the cutoff of its account is refused on every protected route (ADR-004 D5), against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTokenBeforeCutoffTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String OTHER_ACCOUNT = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String EVENTS = "/api/v1/identity-audit/internal/tenants/" + TENANT + "/events";
  /** A protected route that is not built, so passing the filter shows as the 404 of an unknown route. */
  private static final String PROTECTED = "/api/v1/identity-audit/tenants/not-built/audit-records";
  /** A second-precision instant, as the {@code iat} of a token is. */
  private static final Instant R = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("processed_events", "account_session_cutoffs")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static void revokeFrom(String account, Instant from) {
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> body = Map.of(
        "eventId", eventId, "eventType", "AccountSessionsRevoked", "eventVersion", 1, "tenantId", TENANT,
        "aggregateId", account, "payload", Map.of("accountId", account, "revokedFrom", from.toString()),
        "occurredAt", from.toString());
    String service = Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
    assertThat(given().header("Authorization", "Bearer " + service).header("Idempotency-Key", eventId)
        .contentType(ContentType.JSON).body(body).post(EVENTS).statusCode()).isEqualTo(201);
  }

  private static JWTClaimsSet.Builder admin(String account, Instant issuedAt) {
    JWTClaimsSet.Builder claims = Tokens.claims(account, 600, List.of("PLATFORM_ADMINISTRATOR"), null);
    return issuedAt == null ? claims : claims.issueTime(Date.from(issuedAt));
  }

  private static Response call(JWTClaimsSet.Builder claims) {
    return given().header("Authorization", "Bearer " + Tokens.valid(claims)).get(PROTECTED);
  }

  @Test
  void C_token_issued_before_the_cutoff_is_401_and_a_later_one_passes() {
    revokeFrom(ACCOUNT, R);

    Response before = call(admin(ACCOUNT, R.minusSeconds(60)));
    assertThat(before.statusCode()).isEqualTo(401);
    assertThat(before.jsonPath().getString("error")).isEqualTo("UNAUTHORIZED");
    assertThat(before.jsonPath().getString("traceId")).isEqualTo(before.header("X-Correlation-Id"));
    assertThat(before.header("WWW-Authenticate")).startsWith("Bearer");

    assertThat(call(admin(ACCOUNT, R.plusSeconds(60))).statusCode()).as("issued after the cutoff").isEqualTo(404);
    assertThat(call(admin(ACCOUNT, R)).statusCode()).as("issued in the second of the cutoff, as a new sign-in is").isEqualTo(404);
  }

  @Test
  void C_the_second_before_the_cutoff_is_refused_on_every_protected_route_and_a_later_cutoff_moves_forward() {
    revokeFrom(ACCOUNT, R);
    String old = Tokens.valid(admin(ACCOUNT, R.minusSeconds(1)));

    for (String route : List.of(PROTECTED, "/api/v1/identity-audit/tenants", "/api/v1/identity-audit/tenants/" + TENANT)) {
      assertThat(given().header("Authorization", "Bearer " + old).get(route).statusCode()).as(route).isEqualTo(401);
    }

    revokeFrom(ACCOUNT, R.plusSeconds(120));
    assertThat(call(admin(ACCOUNT, R.plusSeconds(60))).statusCode()).as("a token that passed before is refused by the newer cutoff").isEqualTo(401);
    assertThat(call(admin(ACCOUNT, R.plusSeconds(180))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_a_token_without_an_issue_time_is_refused_only_when_its_account_has_a_cutoff() {
    assertThat(call(admin(ACCOUNT, null)).statusCode()).as("no cutoff, nothing to compare").isEqualTo(404);

    revokeFrom(ACCOUNT, R);

    assertThat(call(admin(ACCOUNT, null)).statusCode()).as("it cannot be shown to be later").isEqualTo(401);
  }

  @Test
  void C_the_cutoff_of_one_account_does_not_touch_another_or_a_service() {
    revokeFrom(ACCOUNT, R);

    assertThat(call(admin(OTHER_ACCOUNT, R.minusSeconds(60))).statusCode()).isEqualTo(404);
    String service = Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null).issueTime(Date.from(R.minusSeconds(60))));
    assertThat(given().header("Authorization", "Bearer " + service).get("/api/v1/identity-audit/internal/outbox").statusCode())
        .isEqualTo(200);
  }
}
