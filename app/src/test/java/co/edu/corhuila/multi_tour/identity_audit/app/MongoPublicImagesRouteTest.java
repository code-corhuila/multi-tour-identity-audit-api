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

/** The public profile with the logo and the images, and the files themselves with no sign-in (spec 012: RN-IDA-API-124 and -125). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoPublicImagesRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";
  private static final String TENANT = "/api/v1/identity-audit/tenant";
  private static final String PUBLIC = "/api/v1/identity-audit/public/tenants/";

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

  private static RequestSpecification administratorOf(String tenantId) {
    String token = Tokens.valid(Tokens.claims("account-7", 600, List.of("TENANT_ADMINISTRATOR"), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String active(String identifier) {
    String id = service().header("Idempotency-Key", "onboard:" + identifier)
        .body(Map.of("identifier", identifier, "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO"))
        .post(INTERNAL).jsonPath().getString("tenantId");
    service().body(Map.of("firstAdministratorMembershipId", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", "initialStatus", "ACTIVE"))
        .post(INTERNAL + "/" + id + "/complete-onboarding").then().statusCode(200);
    return id;
  }

  private static byte[] png(int size, int seed) {
    byte[] bytes = new byte[size];
    byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    bytes[size - 1] = (byte) seed;
    return bytes;
  }

  private static Map<String, String> body(byte[] file) {
    return Map.of("contentType", "image/png", "data", Base64.getEncoder().encodeToString(file));
  }

  private static Response profile(String identifier) {
    return given().filter(Contract.filter()).get(PUBLIC + identifier);
  }

  @Test
  void C_a_tenant_without_a_logo_or_images_returns_none() {
    active("acme-tours");
    Response response = profile("acme-tours");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("logoUrl")).isNull();
    assertThat(response.jsonPath().getList("images")).isEmpty();
  }

  @Test
  void C_the_public_profile_returns_the_logo_and_the_images() {
    String tenant = active("acme-tours");
    administratorOf(tenant).body(body(png(300, 1))).put(TENANT + "/logo").then().statusCode(200);
    administratorOf(tenant).body(body(png(400, 2))).put(TENANT + "/images/3").then().statusCode(200);
    administratorOf(tenant).body(body(png(500, 3))).put(TENANT + "/images/1").then().statusCode(200);
    Response response = profile("acme-tours");
    assertThat(response.jsonPath().getString("logoUrl")).startsWith(PUBLIC + "acme-tours/logo?v=");
    assertThat(response.jsonPath().getList("images.position")).containsExactly(1, 3);
    assertThat(response.jsonPath().getString("images[0].url")).startsWith(PUBLIC + "acme-tours/images/1?v=");
  }

  @Test
  void C_a_file_is_served_with_its_kind_and_the_headers_that_keep_it_an_image() {
    String tenant = active("acme-tours");
    byte[] file = png(300, 9);
    administratorOf(tenant).body(body(file)).put(TENANT + "/logo").then().statusCode(200);
    String url = profile("acme-tours").jsonPath().getString("logoUrl");
    Response served = given().filter(Contract.filter()).get(url);
    assertThat(served.statusCode()).isEqualTo(200);
    assertThat(served.asByteArray()).isEqualTo(file);
    assertThat(served.contentType()).isEqualTo("image/png");
    assertThat(served.header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(served.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
    assertThat(served.header("Content-Disposition")).isEqualTo("inline");
    assertThat(served.header("Cache-Control")).isEqualTo("public, max-age=300");
  }

  @Test
  void C_the_url_changes_when_the_file_changes() {
    String tenant = active("acme-tours");
    administratorOf(tenant).body(body(png(300, 1))).put(TENANT + "/logo").then().statusCode(200);
    String first = profile("acme-tours").jsonPath().getString("logoUrl");
    administratorOf(tenant).body(body(png(300, 2))).put(TENANT + "/logo").then().statusCode(200);
    String second = profile("acme-tours").jsonPath().getString("logoUrl");
    assertThat(second).isNotEqualTo(first);
    assertThat(second.substring(0, second.indexOf('?'))).isEqualTo(first.substring(0, first.indexOf('?')));
  }

  @Test
  void C_an_unknown_tenant_or_a_missing_file_or_a_bad_position_is_404() {
    String tenant = active("acme-tours");
    administratorOf(tenant).body(body(png(300, 1))).put(TENANT + "/images/0").then().statusCode(200);
    for (String path : List.of("nobody/logo", "acme-tours/logo", "acme-tours/images/1", "acme-tours/images/5", "acme-tours/images/x",
        "acme-tours/images/-1")) {
      Response response = given().filter(Contract.answers()).get(PUBLIC + path);
      assertThat(response.statusCode()).as(path).isEqualTo(404);
      assertThat(response.jsonPath().getString("error")).isEqualTo("NOT_FOUND");
    }
    assertThat(given().filter(Contract.filter()).get(PUBLIC + "acme-tours/images/0").statusCode()).isEqualTo(200);
  }

  @Test
  void C_an_identifier_that_is_not_one_answers_as_the_profile_does() {
    for (String path : List.of("Bad_Id/logo", "Bad_Id/images/0")) {
      Response response = given().filter(Contract.answers()).get(PUBLIC + path);
      assertThat(response.statusCode()).as(path).isEqualTo(401);
    }
  }

  @Test
  void C_an_inactive_tenant_is_served_and_a_pending_one_is_not() {
    String tenant = active("acme-tours");
    administratorOf(tenant).body(body(png(300, 1))).put(TENANT + "/logo").then().statusCode(200);
    given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON)
        .body(Map.of("reason", "stopped")).post(TENANTS + "/" + tenant + "/inactivate").then().statusCode(200);
    assertThat(given().filter(Contract.filter()).get(PUBLIC + "acme-tours/logo").statusCode()).isEqualTo(200);
    assertThat(profile("acme-tours").jsonPath().getString("logoUrl")).isNotNull();
    service().header("Idempotency-Key", "onboard:pending-tours")
        .body(Map.of("identifier", "pending-tours", "commercialName", "Tours", "timeZone", "America/Bogota", "country", "CO"))
        .post(INTERNAL).then().statusCode(201);
    assertThat(given().filter(Contract.answers()).get(PUBLIC + "pending-tours/logo").statusCode()).isEqualTo(404);
  }

  @Test
  void C_the_files_of_one_tenant_are_never_served_for_another() {
    String a = active("acme-tours");
    active("other-tours");
    byte[] file = png(300, 5);
    administratorOf(a).body(body(file)).put(TENANT + "/logo").then().statusCode(200);
    assertThat(given().filter(Contract.answers()).get(PUBLIC + "other-tours/logo").statusCode()).isEqualTo(404);
    assertThat(given().filter(Contract.filter()).get(PUBLIC + "acme-tours/logo").asByteArray()).isEqualTo(file);
  }
}
