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

/** GET /tenants and GET /tenants/{tenantId} for the Platform Administrator, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTenantReadRoutesTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";
  private static final String ADMIN_MEMBERSHIP = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
  }

  private static RequestSpecification service(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification admin() {
    return given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin()));
  }

  private static String register(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Tours " + identifier, "timeZone", "America/Bogota", "country", "CO");
    return service(service()).header("Idempotency-Key", "onboard:" + identifier).body(body).post(INTERNAL)
        .jsonPath().getString("tenantId");
  }

  /** Three tenants: one active, one failed and one still pending. */
  private String[] seed() {
    String active = register("active-tours");
    service(service()).body(Map.of("firstAdministratorMembershipId", ADMIN_MEMBERSHIP, "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + active + "/complete-onboarding").then().statusCode(200);
    String failed = register("failed-tours");
    service(service()).body(Map.of("failedStep", "CREATE_ACCOUNT"))
        .post(INTERNAL + "/" + failed + "/fail-onboarding").then().statusCode(200);
    return new String[] {active, failed, register("pending-tours")};
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_the_list_has_every_tenant_newest_first_with_its_meta_and_the_default_limit_of_20() {
    seed();
    Response response = admin().filter(Contract.filter()).get(TENANTS);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data")).hasSize(3);
    assertThat(response.jsonPath().getInt("meta.page")).isEqualTo(1);
    assertThat(response.jsonPath().getInt("meta.limit")).isEqualTo(20);
    assertThat(response.jsonPath().getInt("meta.total")).isEqualTo(3);
    assertThat(response.jsonPath().getInt("meta.totalPages")).isEqualTo(1);
    List<java.time.Instant> created =
        response.jsonPath().getList("data.createdAt", String.class).stream().map(java.time.Instant::parse).toList();
    assertThat(created).isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  @Test
  void C_a_page_and_a_status_narrow_the_list_and_a_page_past_the_end_is_empty_with_the_same_total() {
    seed();
    Response second = admin().filter(Contract.filter()).queryParam("limit", 2).queryParam("page", 2).get(TENANTS);
    assertThat(second.jsonPath().getList("data")).hasSize(1);
    assertThat(second.jsonPath().getInt("meta.totalPages")).isEqualTo(2);
    Response active = admin().filter(Contract.filter()).queryParam("status", "ACTIVE").get(TENANTS);
    assertThat(active.jsonPath().getList("data.identifier")).containsExactly("active-tours");
    assertThat(active.jsonPath().getInt("meta.total")).isEqualTo(1);
    Response past = admin().filter(Contract.filter()).queryParam("page", 9).get(TENANTS);
    assertThat(past.jsonPath().getList("data")).isEmpty();
    assertThat(past.jsonPath().getInt("meta.total")).isEqualTo(3);
  }

  @Test
  void C_a_limit_a_page_or_a_status_out_of_range_and_a_filter_not_declared_are_a_400_not_a_trim() {
    seed();
    for (Map<String, Object> bad : List.of(
        Map.<String, Object>of("limit", 0), Map.<String, Object>of("limit", 101), Map.<String, Object>of("page", 0),
        Map.<String, Object>of("page", "abc"), Map.<String, Object>of("limit", ""), Map.<String, Object>of("status", "BAD"),
        Map.<String, Object>of("status", "active"), Map.<String, Object>of("name", "x"))) {
      assertRefused(admin().filter(Contract.answers()).queryParams(bad).get(TENANTS), 400, "VALIDATION_ERROR");
    }
    assertRefused(admin().filter(Contract.answers()).get(TENANTS + "?page=1&page=2"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_one_tenant_is_read_whatever_its_status_and_an_unknown_one_is_a_404_with_the_fixed_message() {
    String[] ids = seed();
    for (String id : ids) {
      Response response = admin().filter(Contract.filter()).get(TENANTS + "/" + id);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.jsonPath().getString("tenantId")).isEqualTo(id);
    }
    Response missing = admin().filter(Contract.answers()).get(TENANTS + "/11111111-2222-4333-8444-555555555555");
    assertRefused(missing, 404, "NOT_FOUND");
    assertThat(missing.jsonPath().getString("message")).isEqualTo("There is no such tenant");
    assertRefused(admin().filter(Contract.answers()).get(TENANTS + "/not-a-uuid"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_without_a_token_a_401_and_with_a_service_token_a_403_on_both_routes() {
    String id = seed()[0];
    for (String path : List.of(TENANTS, TENANTS + "/" + id)) {
      assertRefused(given().filter(Contract.answers()).get(path), 401, "UNAUTHORIZED");
      Response service = given().header("Authorization", "Bearer " + service()).filter(Contract.answers()).get(path);
      assertRefused(service, 403, "FORBIDDEN");
    }
  }
}
