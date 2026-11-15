package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** PUT and DELETE of the logo and the commercial images of the caller's tenant (spec 012), against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTenantImagesRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";
  private static final String LOGO = "/api/v1/identity-audit/tenant/logo";
  private static final String IMAGES = "/api/v1/identity-audit/tenant/images/";

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys", "tenant_images")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification service() {
    String token = Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification platform() {
    return given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims("account-7", 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification administratorOf(String tenantId) {
    return as("TENANT_ADMINISTRATOR", tenantId);
  }

  private static String active(String identifier) {
    String id = service().header("Idempotency-Key", "onboard:" + identifier)
        .body(Map.of("identifier", identifier, "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO"))
        .post(INTERNAL).jsonPath().getString("tenantId");
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + id + "/complete-onboarding").then().statusCode(200);
    return id;
  }

  private static byte[] png(int size) {
    byte[] bytes = new byte[size];
    byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    return bytes;
  }

  private static Map<String, String> body(String contentType, byte[] file) {
    return Map.of("contentType", contentType, "data", Base64.getEncoder().encodeToString(file));
  }

  private long stored(String tenantId) {
    return client.getDatabase(database).getCollection("tenant_images").countDocuments(new Document("tenantId", tenantId));
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_the_administrator_saves_replaces_and_removes_the_logo() {
    String tenant = active("acme-tours");
    Response saved = administratorOf(tenant).filter(Contract.filter()).body(body("image/png", png(300))).put(LOGO);
    assertThat(saved.statusCode()).as("%s", saved.asString()).isEqualTo(200);
    assertThat(saved.jsonPath().getString("tenantId")).isEqualTo(tenant);
    assertThat(stored(tenant)).isEqualTo(1);
    administratorOf(tenant).filter(Contract.filter()).body(body("image/png", png(500))).put(LOGO).then().statusCode(200);
    assertThat(stored(tenant)).isEqualTo(1);
    administratorOf(tenant).filter(Contract.filter()).delete(LOGO).then().statusCode(204);
    administratorOf(tenant).filter(Contract.filter()).delete(LOGO).then().statusCode(204);
    assertThat(stored(tenant)).isZero();
  }

  @Test
  void C_the_administrator_saves_and_removes_each_of_the_five_images() {
    String tenant = active("acme-tours");
    for (int position = 0; position < 5; position++) {
      administratorOf(tenant).filter(Contract.filter()).body(body("image/png", png(100 + position))).put(IMAGES + position)
          .then().statusCode(200);
    }
    assertThat(stored(tenant)).isEqualTo(5);
    administratorOf(tenant).filter(Contract.filter()).delete(IMAGES + 2).then().statusCode(204);
    assertThat(stored(tenant)).isEqualTo(4);
  }

  @Test
  void C_only_the_tenant_administrator_changes_the_logo_and_the_images() {
    String tenant = active("acme-tours");
    Map<String, String> file = body("image/png", png(100));
    assertRefused(given().contentType(ContentType.JSON).filter(Contract.answers()).body(file).put(LOGO), 401, "UNAUTHORIZED");
    for (RequestSpecification caller : List.of(platform(), service(), as("MANAGER", tenant), as("OPERATIONAL_COLLABORATOR", tenant), as("FINAL_CUSTOMER", tenant))) {
      assertRefused(caller.filter(Contract.answers()).body(file).put(LOGO), 403, "FORBIDDEN");
      assertRefused(caller.filter(Contract.answers()).body(file).put(IMAGES + 0), 403, "FORBIDDEN");
      assertRefused(caller.filter(Contract.answers()).delete(LOGO), 403, "FORBIDDEN");
      assertRefused(caller.filter(Contract.answers()).delete(IMAGES + 0), 403, "FORBIDDEN");
    }
    assertThat(stored(tenant)).isZero();
  }

  @Test
  void C_the_tenant_is_the_one_of_the_token_and_a_body_that_names_one_is_a_400() {
    String a = active("acme-tours");
    String b = active("other-tours");
    administratorOf(a).filter(Contract.filter()).body(body("image/png", png(100))).put(LOGO).then().statusCode(200);
    assertThat(stored(a)).isEqualTo(1);
    assertThat(stored(b)).isZero();
    Map<String, String> naming = Map.of("contentType", "image/png", "data", Base64.getEncoder().encodeToString(png(100)), "tenantId", b);
    assertRefused(administratorOf(a).filter(Contract.answers()).body(naming).put(LOGO), 400, "VALIDATION_ERROR");
    assertThat(stored(b)).isZero();
  }

  @Test
  void C_a_file_that_is_not_allowed_is_400_and_the_previous_one_stays() {
    String tenant = active("acme-tours");
    administratorOf(tenant).filter(Contract.filter()).body(body("image/png", png(100))).put(LOGO).then().statusCode(200);
    byte[] gif = "GIF89a-----------".getBytes();
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", gif)).put(LOGO), 400, "VALIDATION_ERROR");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/jpeg", png(100))).put(LOGO), 400, "VALIDATION_ERROR");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(256 * 1024 + 1))).put(LOGO), 400, "VALIDATION_ERROR");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(Map.of("contentType", "image/png", "data", "not base64!!")).put(LOGO), 400, "VALIDATION_ERROR");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(Map.of("contentType", "image/png")).put(LOGO), 400, "VALIDATION_ERROR");
    assertThat(stored(tenant)).isEqualTo(1);
    Document kept = client.getDatabase(database).getCollection("tenant_images").find(new Document("tenantId", tenant)).first();
    assertThat(kept.getInteger("sizeBytes")).isEqualTo(100);
  }

  @Test
  void C_a_position_out_of_range_is_400() {
    String tenant = active("acme-tours");
    Map<String, String> file = body("image/png", png(100));
    for (String position : List.of("5", "-1", "x", "99999999999", "1.5")) {
      assertRefused(administratorOf(tenant).filter(Contract.answers()).body(file).put(IMAGES + position), 400, "VALIDATION_ERROR");
      assertRefused(administratorOf(tenant).filter(Contract.answers()).delete(IMAGES + position), 400, "VALIDATION_ERROR");
    }
    assertThat(stored(tenant)).isZero();
  }

  @Test
  void C_an_inactive_tenant_cannot_change_them() {
    String tenant = active("acme-tours");
    platform().body(Map.of("reason", "stopped")).post(TENANTS + "/" + tenant + "/inactivate").then().statusCode(200);
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(100))).put(LOGO), 422, "BUSINESS_RULE_VIOLATION");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).delete(LOGO), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(stored(tenant)).isZero();
  }

  @Test
  void C_only_the_tenant_administrator_gets_the_big_body_and_a_disguised_path_never_does() {
    String tenant = active("acme-tours");
    Map<String, String> big = body("image/png", png(100 * 1024));
    for (RequestSpecification caller : List.of(platform(), service(), as("MANAGER", tenant), as("FINAL_CUSTOMER", tenant))) {
      assertRefused(caller.filter(Contract.answers()).body(big).put(LOGO), 413, "PAYLOAD_TOO_LARGE");
      assertRefused(caller.filter(Contract.answers()).body(big).put(IMAGES + 0), 413, "PAYLOAD_TOO_LARGE");
    }
    for (String path : List.of(LOGO + "/x", IMAGES, IMAGES + "1/extra", IMAGES + "..;x", IMAGES + "%2e%2e/x")) {
      Response disguised = administratorOf(tenant).filter(Contract.answers()).body(big).put(path);
      assertThat(disguised.statusCode()).as(path).isIn(403, 404, 413);
    }
    assertThat(stored(tenant)).isZero();
  }

  @Test
  void C_removing_an_image_that_is_not_there_is_204_and_an_inactive_tenant_cannot_remove_one() {
    String tenant = active("acme-tours");
    administratorOf(tenant).filter(Contract.filter()).delete(IMAGES + 3).then().statusCode(204);
    platform().body(Map.of("reason", "stopped")).post(TENANTS + "/" + tenant + "/inactivate").then().statusCode(200);
    assertRefused(administratorOf(tenant).filter(Contract.answers()).delete(IMAGES + 3), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_the_file_routes_accept_a_big_body_and_the_others_do_not() {
    String tenant = active("acme-tours");
    administratorOf(tenant).filter(Contract.filter()).body(body("image/png", png(1024 * 1024))).put(IMAGES + 0).then().statusCode(200);
    // 1 MB of file is a 400 only when its body fits; a body of 1.6 MB is over the limit of the route
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(1200 * 1024))).put(IMAGES + 1), 413, "PAYLOAD_TOO_LARGE");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(1024 * 1024 + 1))).put(IMAGES + 1), 400, "VALIDATION_ERROR");
    assertRefused(administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(300 * 1024))).put(LOGO), 400, "VALIDATION_ERROR");
    Response huge = administratorOf(tenant).filter(Contract.answers()).body(body("image/png", png(1200 * 1024 + 400 * 1024))).put(IMAGES + 1);
    assertRefused(huge, 413, "PAYLOAD_TOO_LARGE");
    Response elsewhere = administratorOf(tenant).filter(Contract.answers())
        .body(Map.of("primaryColor", "#112233", "secondaryColor", "#445566", "pad", "x".repeat(20 * 1024)))
        .put("/api/v1/identity-audit/tenant/visual-identity");
    assertRefused(elsewhere, 413, "PAYLOAD_TOO_LARGE");
  }
}
