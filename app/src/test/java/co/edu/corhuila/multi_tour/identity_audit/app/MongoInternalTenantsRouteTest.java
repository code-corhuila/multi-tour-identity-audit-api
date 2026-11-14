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

/** GET /internal/tenants for the services (spec 011), against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoInternalTenantsRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
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
    return Tokens.valid(Tokens.claims("worker-retention", 600, List.of("SERVICE"), null));
  }

  private static RequestSpecification asService() {
    return given().header("Authorization", "Bearer " + service()).contentType(ContentType.JSON);
  }

  private static String register(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Tours " + identifier, "timeZone", "America/Bogota", "country", "CO");
    return asService().header("Idempotency-Key", "onboard:" + identifier).body(body).post(INTERNAL)
        .jsonPath().getString("tenantId");
  }

  /** An active tenant, an inactive one, a failed one and a pending one; returns the ids in that order. */
  private String[] seed() {
    String active = register("active-tours");
    asService().body(Map.of("firstAdministratorMembershipId", ADMIN_MEMBERSHIP, "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + active + "/complete-onboarding").then().statusCode(200);
    String inactive = register("inactive-tours");
    asService().body(Map.of("firstAdministratorMembershipId", ADMIN_MEMBERSHIP, "initialStatus", "INACTIVE"))
        .post(INTERNAL + "/" + inactive + "/complete-onboarding").then().statusCode(200);
    String failed = register("failed-tours");
    asService().body(Map.of("failedStep", "CREATE_ACCOUNT")).post(INTERNAL + "/" + failed + "/fail-onboarding").then().statusCode(200);
    return new String[] {active, inactive, failed, register("pending-tours")};
  }

  /** The tenants seeded by this test, oldest first, read back through the route of the Platform Administrator. */
  private static String[] idsOldestFirst() {
    List<String> newestFirst = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin()))
        .get("/api/v1/identity-audit/tenants").jsonPath().getList("data.tenantId");
    return newestFirst.reversed().toArray(new String[0]);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_a_service_lists_every_tenant_by_default() {
    seed();
    Response response = asService().filter(Contract.filter()).get(INTERNAL);
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data.status")).containsExactlyInAnyOrder("ACTIVE", "INACTIVE", "FAILED", "PENDING");
    assertThat(response.jsonPath().getInt("meta.total")).isEqualTo(4);
    assertThat(response.jsonPath().getInt("meta.limit")).isEqualTo(20);
    Response all = asService().filter(Contract.filter()).queryParam("status", "ALL").get(INTERNAL);
    assertThat(all.jsonPath().getInt("meta.total")).isEqualTo(4);
  }

  @Test
  void C_the_status_filter_limits_the_list() {
    String[] ids = seed();
    Response inactive = asService().filter(Contract.filter()).queryParam("status", "INACTIVE").get(INTERNAL);
    assertThat(inactive.jsonPath().getList("data.tenantId")).containsExactly(ids[1]);
    Response active = asService().filter(Contract.filter()).queryParam("status", "ACTIVE").get(INTERNAL);
    assertThat(active.jsonPath().getList("data.tenantId")).containsExactly(ids[0]);
  }

  @Test
  void C_an_item_carries_only_the_tenant_and_its_status() {
    seed();
    Response response = asService().filter(Contract.filter()).get(INTERNAL);
    List<Map<String, Object>> items = response.jsonPath().getList("data");
    assertThat(items).isNotEmpty().allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("tenantId", "status"));
  }

  @Test
  void C_an_unknown_status_is_a_400() {
    seed();
    for (String bad : List.of("BAD", "active", "PENDING", "FAILED", "")) {
      assertRefused(asService().filter(Contract.answers()).queryParam("status", bad).get(INTERNAL), 400, "VALIDATION_ERROR");
    }
  }

  @Test
  void C_the_paging_limits_are_400_not_trimmed_and_an_undeclared_filter_is_400() {
    seed();
    for (Map<String, Object> bad : List.of(
        Map.<String, Object>of("limit", 0), Map.<String, Object>of("limit", 101), Map.<String, Object>of("page", 0),
        Map.<String, Object>of("page", "abc"), Map.<String, Object>of("name", "x"))) {
      assertRefused(asService().filter(Contract.answers()).queryParams(bad).get(INTERNAL), 400, "VALIDATION_ERROR");
    }
    assertRefused(asService().filter(Contract.answers()).get(INTERNAL + "?status=ACTIVE&status=ALL"), 400, "VALIDATION_ERROR");
    String[] ids = idsOldestFirst();
    Response first = asService().filter(Contract.filter()).queryParam("limit", 3).get(INTERNAL);
    assertThat(first.jsonPath().getList("data.tenantId")).doesNotContain(ids[0]);
    Response second = asService().filter(Contract.filter()).queryParam("limit", 3).queryParam("page", 2).get(INTERNAL);
    assertThat(second.jsonPath().getList("data")).hasSize(1);
    assertThat(second.jsonPath().getList("data.tenantId")).containsExactly(ids[0]);
    assertThat(second.jsonPath().getInt("meta.totalPages")).isEqualTo(2);
    Response past = asService().filter(Contract.filter()).queryParam("page", 9).get(INTERNAL);
    assertThat(past.jsonPath().getList("data")).isEmpty();
    assertThat(past.jsonPath().getInt("meta.total")).isEqualTo(4);
  }

  @Test
  void C_without_a_token_it_is_401_and_a_person_is_403() {
    seed();
    assertRefused(given().filter(Contract.answers()).get(INTERNAL), 401, "UNAUTHORIZED");
    Response admin = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).filter(Contract.answers()).get(INTERNAL);
    assertRefused(admin, 403, "FORBIDDEN");
  }
}
