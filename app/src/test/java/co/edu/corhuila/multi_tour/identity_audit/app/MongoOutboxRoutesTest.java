package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** The routes of the outbox that job 7 calls, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoOutboxRoutesTest extends ShellTest {

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

  /** A tenant that finished its onboarding: one event TenantCreated waits for job 7. */
  private static void tenantWithEvent(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO");
    String id = service().header("Idempotency-Key", "onboard:" + identifier).body(body).post(INTERNAL)
        .jsonPath().getString("tenantId");
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + id + "/complete-onboarding").then().statusCode(200);
  }

  private static Response pending(String query) {
    return service().filter(Contract.filter()).get(OUTBOX + query);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_the_list_has_the_pending_events_with_the_envelope_and_the_payload_and_a_published_one_leaves_it() {
    tenantWithEvent("acme-tours");
    tenantWithEvent("other-tours");
    Response all = pending("");
    assertThat(all.statusCode()).isEqualTo(200);
    assertThat(all.jsonPath().getList("data")).hasSize(2);
    assertThat(all.jsonPath().getInt("meta.total")).isEqualTo(2);
    assertThat(all.jsonPath().getInt("meta.limit")).isEqualTo(20);
    assertThat(all.jsonPath().getString("data[0].eventType")).isEqualTo("TenantCreated");
    assertThat(all.jsonPath().getString("data[0].status")).isEqualTo("PENDING");
    assertThat(all.jsonPath().getString("data[0].payload.tenantStatus")).isEqualTo("ACTIVE");
    assertThat(all.jsonPath().getInt("data[0].attempts")).isZero();
    String first = all.jsonPath().getString("data[0].eventId");
    assertThat(pending("?limit=1&page=2").jsonPath().getList("data")).hasSize(1);
    assertThat(pending("?status=PENDING").jsonPath().getInt("meta.total")).isEqualTo(2);
    service().post(OUTBOX + "/" + first + "/published").then().statusCode(200);
    assertThat(pending("").jsonPath().getList("data.eventId")).doesNotContain(first).hasSize(1);
  }

  @Test
  void C_a_limit_a_page_or_a_status_out_of_range_and_a_filter_not_declared_are_a_400() {
    for (String bad : List.of("?limit=0", "?limit=101", "?page=0", "?page=abc", "?page=+1", "?status=PUBLISHED",
        "?status=pending", "?status=", "?name=x", "?page=1&page=2", "?status=PENDING&status=PENDING")) {
      assertRefused(service().filter(Contract.answers()).get(OUTBOX + bad), 400, "VALIDATION_ERROR");
    }
    assertThat(pending("?limit=100").statusCode()).isEqualTo(200);
  }

  @Test
  void C_publishing_marks_the_event_once_and_a_repeat_is_a_200_with_the_same_time() {
    tenantWithEvent("acme-tours");
    String id = pending("").jsonPath().getString("data[0].eventId");
    Response done = service().filter(Contract.filter()).post(OUTBOX + "/" + id + "/published");
    assertThat(done.statusCode()).isEqualTo(200);
    assertThat(done.jsonPath().getString("status")).isEqualTo("PUBLISHED");
    assertThat(done.jsonPath().getString("publishedAt")).isNotNull();
    Response again = service().filter(Contract.filter()).post(OUTBOX + "/" + id + "/published");
    assertThat(again.jsonPath().getString("publishedAt")).isEqualTo(done.jsonPath().getString("publishedAt"));
  }

  @Test
  void C_an_event_that_does_not_exist_is_a_404_with_the_fixed_message_and_an_id_that_is_not_a_uuid_a_400() {
    Response missing = service().filter(Contract.answers()).post(OUTBOX + "/" + UNKNOWN + "/published");
    assertRefused(missing, 404, "NOT_FOUND");
    assertThat(missing.jsonPath().getString("message")).isEqualTo("There is no such event");
    assertRefused(service().filter(Contract.answers()).post(OUTBOX + "/not-a-uuid/published"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_without_a_token_a_401_and_with_a_person_token_a_403_on_both_routes() {
    tenantWithEvent("acme-tours");
    String id = pending("").jsonPath().getString("data[0].eventId");
    String person = Tokens.valid(Tokens.admin());
    assertRefused(given().filter(Contract.answers()).get(OUTBOX), 401, "UNAUTHORIZED");
    assertRefused(given().filter(Contract.answers()).post(OUTBOX + "/" + id + "/published"), 401, "UNAUTHORIZED");
    assertRefused(given().header("Authorization", "Bearer " + person).filter(Contract.answers()).get(OUTBOX), 403, "FORBIDDEN");
    assertRefused(given().header("Authorization", "Bearer " + person).filter(Contract.answers())
        .post(OUTBOX + "/" + id + "/published"), 403, "FORBIDDEN");
    assertThat(pending("").jsonPath().getList("data")).hasSize(1);
  }
}
