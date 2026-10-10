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
import org.springframework.core.env.Environment;

/** POST /api/v1/identity-audit/internal/tenants: step 1 of the saga, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoRegisterRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/identity-audit/internal/tenants";
  private static final Map<String, Object> BODY =
      Map.of("identifier", "acme-tours", "commercialName", "Acme Tours", "timeZone", "America/Bogota", "country", "CO");

  @Autowired MongoClient client;
  @Autowired Environment environment;

  @BeforeEach
  void aCleanDatabase() {
    String database = environment.getProperty("MONGODB_URI").replaceAll("^.*/([^/?]+)(\\?.*)?$", "$1");
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static String service() {
    return "Bearer " + Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
  }

  private static RequestSpecification asService(String key) {
    RequestSpecification request = given().header("Authorization", service()).contentType(ContentType.JSON);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private static Response register(Map<String, Object> body, String key) {
    return asService(key).filter(Contract.answers()).body(body).post(ROUTE);
  }

  @Test
  void C_registering_creates_a_pending_tenant_201_with_its_location_and_matches_the_contract() {
    Response response = asService("onboard:1:step-1").filter(Contract.filter()).body(BODY).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("PENDING");
    assertThat(response.jsonPath().getString("firstAdministratorMembershipId")).isNull();
    assertThat(response.header("Location"))
        .isEqualTo("/api/v1/identity-audit/tenants/" + response.jsonPath().getString("tenantId"));
  }

  @Test
  void C_the_same_key_again_is_a_200_with_the_first_tenant_whatever_the_body_says_now() {
    String first = register(BODY, "onboard:1:step-1").jsonPath().getString("tenantId");
    Response again = register(Map.of("identifier", "Not_Valid_Now"), "onboard:1:step-1");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("tenantId")).isEqualTo(first);
    asService("onboard:1:step-1").filter(Contract.filter()).body(BODY).post(ROUTE).then().statusCode(200);
  }

  @Test
  void C_each_field_that_is_missing_or_invalid_is_a_400_and_nothing_is_created() {
    List<Map<String, Object>> bad = List.of(
        Map.of("identifier", "Acme_Tours", "commercialName", "Acme", "timeZone", "America/Bogota", "country", "CO"),
        Map.of("identifier", "acme-tours", "commercialName", "  ", "timeZone", "America/Bogota", "country", "CO"),
        Map.of("identifier", "acme-tours", "commercialName", "Acme", "timeZone", "Mars/Olympus", "country", "CO"),
        Map.of("identifier", "acme-tours", "commercialName", "Acme", "timeZone", "America/Bogota", "country", "COL"),
        Map.of("identifier", "acme-tours", "commercialName", "Acme", "timeZone", "America/Bogota"),
        Map.of("identifier", "acme-tours", "commercialName", "Acme", "timeZone", "America/Bogota", "country", "CO", "extra", 1),
        Map.of("identifier", 5, "commercialName", "Acme", "timeZone", "America/Bogota", "country", "CO"));
    int n = 0;
    for (Map<String, Object> body : bad) {
      Response response = register(body, "onboard:bad:" + n++);
      assertThat(response.statusCode()).as("%s", body).isEqualTo(400);
      assertThat(response.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(asService("onboard:bad:x").filter(Contract.answers()).body("{not json").post(ROUTE).statusCode()).isEqualTo(400);
    assertThat(client.getDatabase("identity_audit").getCollection("tenants").countDocuments()).isZero();
  }

  @Test
  void C_the_idempotency_key_is_required_and_has_8_to_128_characters_without_spaces() {
    for (String key : new String[] {null, "short", "has a space in it", "k".repeat(129)}) {
      Response response = register(BODY, key);
      assertThat(response.statusCode()).as("key %s", key).isEqualTo(400);
      assertThat(response.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(register(BODY, "k".repeat(128)).statusCode()).isEqualTo(201);
  }

  @Test
  void C_without_a_token_the_route_is_a_401_and_with_a_person_token_a_403() {
    Response none = given().filter(Contract.answers()).contentType(ContentType.JSON)
        .header("Idempotency-Key", "onboard:1:step-1").body(BODY).post(ROUTE);
    assertThat(none.statusCode()).isEqualTo(401);
    String person = "Bearer " + Tokens.valid(Tokens.admin());
    Response forbidden = given().filter(Contract.answers()).header("Authorization", person).contentType(ContentType.JSON)
        .header("Idempotency-Key", "onboard:1:step-1").body(BODY).post(ROUTE);
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(forbidden.jsonPath().getString("error")).isEqualTo("FORBIDDEN");
    Response brokenBody = given().filter(Contract.answers()).header("Authorization", person).contentType(ContentType.JSON)
        .header("Idempotency-Key", "onboard:1:step-1").body("{not json").post(ROUTE);
    assertThat(brokenBody.statusCode()).isEqualTo(403);
    assertThat(client.getDatabase("identity_audit").getCollection("tenants").countDocuments()).isZero();
  }

  @Test
  void C_a_live_identifier_is_a_422_that_names_it_and_the_error_carries_the_correlation_id() {
    register(BODY, "onboard:1:step-1");
    Response response = asService("onboard:2:step-1").filter(Contract.answers())
        .header("X-Correlation-Id", "trace-422").body(BODY).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.jsonPath().getString("error")).isEqualTo("BUSINESS_RULE_VIOLATION");
    assertThat(response.jsonPath().getString("message")).contains("acme-tours");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo("trace-422");
  }
}
