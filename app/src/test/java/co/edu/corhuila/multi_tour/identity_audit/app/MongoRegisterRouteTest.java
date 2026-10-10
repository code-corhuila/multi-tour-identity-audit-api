package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** POST /api/v1/identity-audit/internal/tenants: step 1 of the saga, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoRegisterRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/identity-audit/internal/tenants";
  private static final String KEY = "onboard:1:step-1";

  @Autowired MongoClient client;

  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private long count(String collection) {
    return client.getDatabase(database).getCollection(collection).countDocuments();
  }

  private static Map<String, Object> body() {
    Map<String, Object> body = new HashMap<>();
    body.put("identifier", "acme-tours");
    body.put("commercialName", "Acme Tours");
    body.put("timeZone", "America/Bogota");
    body.put("country", "CO");
    return body;
  }

  private static Map<String, Object> with(String field, Object value) {
    Map<String, Object> body = body();
    body.put(field, value);
    return body;
  }

  private static RequestSpecification as(String token, String key) {
    RequestSpecification request = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
  }

  private static Response register(Object body, String key) {
    return as(service(), key).filter(Contract.answers()).body(body).post(ROUTE);
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_registering_creates_a_pending_tenant_201_with_its_location_and_no_event() {
    Response response = as(service(), KEY).filter(Contract.filter()).body(body()).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getString("tenantStatus")).isEqualTo("PENDING");
    assertThat(response.jsonPath().getString("firstAdministratorMembershipId")).isNull();
    assertThat(response.header("Location"))
        .isEqualTo("/api/v1/identity-audit/tenants/" + response.jsonPath().getString("tenantId"));
    assertThat(count("outbox_events")).isZero();
  }

  @Test
  void C_the_same_key_again_is_a_200_with_the_first_tenant_whatever_the_body_says_now() {
    String first = register(body(), KEY).jsonPath().getString("tenantId");
    Response again = register(Map.of("identifier", "Not_Valid_Now"), KEY);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("tenantId")).isEqualTo(first);
    as(service(), KEY).filter(Contract.filter()).body(body()).post(ROUTE).then().statusCode(200);
    assertThat(count("tenants")).isEqualTo(1);
  }

  @Test
  void C_each_field_that_is_missing_null_or_invalid_is_a_400_and_nothing_is_created() {
    int n = 0;
    for (String field : List.of("identifier", "commercialName", "timeZone", "country")) {
      Map<String, Object> missing = body();
      missing.remove(field);
      assertRefused(register(missing, "missing-" + field), 400, "VALIDATION_ERROR");
      assertRefused(register(with(field, null), "null-" + field), 400, "VALIDATION_ERROR");
    }
    List<Map<String, Object>> invalid = List.of(
        with("identifier", "Acme_Tours"), with("identifier", "x".repeat(41)), with("identifier", "ab"),
        with("commercialName", "  "), with("commercialName", "N".repeat(121)),
        with("timeZone", "Mars/Olympus"), with("country", "COL"), with("country", "co"), with("extra", 1),
        with("identifier", 12345678), with("commercialName", 99999), with("commercialName", true));
    for (Map<String, Object> bad : invalid) {
      Response response = register(bad, "invalid-" + n++);
      assertThat(response.statusCode()).as("body %s answered %s", bad, response.asString()).isEqualTo(400);
      assertThat(response.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    String twice = "{\"identifier\":\"a-b\",\"identifier\":\"c-d\",\"commercialName\":\"A\",\"timeZone\":\"UTC\",\"country\":\"CO\"}";
    assertRefused(register(twice, "duplicate-key"), 400, "VALIDATION_ERROR");
    assertRefused(register("{not json", "malformed-json"), 400, "VALIDATION_ERROR");
    assertThat(count("tenants")).isZero();
  }

  @Test
  void C_the_idempotency_key_is_required_and_has_8_to_128_characters_without_spaces() {
    for (String key : new String[] {null, "short", "has a space in it", "k".repeat(129)}) {
      assertRefused(register(body(), key), 400, "VALIDATION_ERROR");
    }
    assertThat(register(body(), "k".repeat(128)).statusCode()).isEqualTo(201);
  }

  @Test
  void C_without_a_token_the_route_is_a_401_and_with_a_person_token_a_403_judged_before_the_body_and_the_key() {
    Response none = given().filter(Contract.answers()).contentType(ContentType.JSON)
        .header("Idempotency-Key", KEY).body(body()).post(ROUTE);
    assertRefused(none, 401, "UNAUTHORIZED");
    String person = Tokens.valid(Tokens.admin());
    assertRefused(as(person, KEY).filter(Contract.answers()).body(body()).post(ROUTE), 403, "FORBIDDEN");
    assertRefused(as(person, KEY).filter(Contract.answers()).body("{not json").post(ROUTE), 403, "FORBIDDEN");
    assertRefused(as(person, null).filter(Contract.answers()).body(body()).post(ROUTE), 403, "FORBIDDEN");
    assertThat(count("tenants")).isZero();
  }

  @Test
  void C_a_body_that_is_not_json_is_a_415() {
    Response response =
        as(service(), KEY).filter(Contract.answers()).contentType("text/plain").body("acme").post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(415);
  }

  @Test
  void C_a_live_identifier_is_a_422_that_names_it_and_the_error_carries_the_correlation_id() {
    register(body(), KEY);
    Response response = as(service(), "onboard:2:step-1").filter(Contract.answers())
        .header("X-Correlation-Id", "trace-422").body(body()).post(ROUTE);
    assertRefused(response, 422, "BUSINESS_RULE_VIOLATION");
    assertThat(response.jsonPath().getString("message")).contains("acme-tours");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo("trace-422");
    assertThat(count("tenants")).isEqualTo(1);
  }
}
