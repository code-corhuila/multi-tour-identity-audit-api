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

/** GET /public/tenants/{identifier}: what anyone with the link may know of a tenant, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoPublicProfileRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String PROFILE = "/api/v1/identity-audit/public/tenants/";

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

  private static String pending(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Tours " + identifier, "timeZone", "America/Bogota", "country", "CO");
    return service().header("Idempotency-Key", "onboard:" + identifier).body(body).post(INTERNAL)
        .jsonPath().getString("tenantId");
  }

  private static void complete(String identifier, String status) {
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", status))
        .post(INTERNAL + "/" + pending(identifier) + "/complete-onboarding").then().statusCode(200);
  }

  private static Response profile(String identifier) {
    return given().filter(Contract.filter()).get(PROFILE + identifier);
  }

  private static Response refusedProfile(String identifier) {
    return given().filter(Contract.answers()).get(PROFILE + identifier);
  }

  @Test
  void C_an_active_tenant_shows_its_name_identifier_and_status_and_nothing_else_without_a_token() {
    complete("acme-tours", "ACTIVE");
    Response response = profile("acme-tours");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getMap("$").keySet()).containsExactlyInAnyOrder("identifier", "commercialName", "tenantStatus");
    assertThat(response.jsonPath().getString("commercialName")).isEqualTo("Tours acme-tours");
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
    assertThat(response.header("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void C_an_inactive_tenant_answers_200_with_its_status_so_the_container_can_show_its_notice() {
    complete("acme-tours", "INACTIVE");
    assertThat(profile("acme-tours").jsonPath().getString("tenantStatus")).isEqualTo("INACTIVE");
  }

  @Test
  void C_a_pending_failed_or_unknown_tenant_is_a_404_with_the_same_answer() {
    pending("pending-tours");
    String failed = pending("failed-tours");
    service().body(Map.of("failedStep", "CREATE_ACCOUNT")).post(INTERNAL + "/" + failed + "/fail-onboarding");
    Map<String, Object> first = null;
    for (String identifier : List.of("pending-tours", "failed-tours", "unknown-tours")) {
      Response response = refusedProfile(identifier);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.jsonPath().getString("error")).isEqualTo("NOT_FOUND");
      Map<String, Object> body = new java.util.HashMap<>(response.jsonPath().getMap("$"));
      body.remove("traceId");
      assertThat(first == null ? body : first).isEqualTo(body);
      first = body;
    }
  }

  @Test
  void C_a_path_that_is_not_written_like_an_identifier_never_reaches_the_profile() {
    complete("acme-tours", "ACTIVE");
    for (String bad : List.of("ACME-TOURS", "acme_tours", "acme%2Dtours", "a".repeat(41), "acme-tours;x=1", "acme-tours/")) {
      assertThat(given().filter(Contract.answers()).get(PROFILE + bad).statusCode()).as(bad).isEqualTo(401);
    }
    Response short2 = refusedProfile("ab");
    assertThat(short2.statusCode()).isEqualTo(404);
  }

  @Test
  void C_a_token_does_not_change_the_answer_of_a_public_route() {
    complete("acme-tours", "ACTIVE");
    Response response = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin()))
        .filter(Contract.filter()).get(PROFILE + "acme-tours");
    assertThat(response.statusCode()).isEqualTo(200);
    String expired = Tokens.valid(Tokens.claims("account-1", -600, List.of("PLATFORM_ADMINISTRATOR"), null));
    for (String bearer : List.of("garbage", expired)) {
      Response ignored = given().header("Authorization", "Bearer " + bearer).get(PROFILE + "acme-tours");
      assertThat(ignored.statusCode()).as(bearer).isEqualTo(200);
    }
  }
}
