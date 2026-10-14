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

/** PUT /tenant/visual-identity and the public profile, against the real database with the db repository applied. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoVisualIdentityRouteTest extends ShellTest {

  private static final String INTERNAL = "/api/v1/identity-audit/internal/tenants";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";
  private static final String ROUTE = "/api/v1/identity-audit/tenant/visual-identity";
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

  private static RequestSpecification admin() {
    return given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
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

  private static Response set(String tenantId, String primary, String secondary) {
    return administratorOf(tenantId).filter(Contract.filter())
        .body(Map.of("primaryColor", primary, "secondaryColor", secondary)).put(ROUTE);
  }

  private static Response profile(String identifier) {
    return given().filter(Contract.filter()).get(PROFILE + identifier);
  }

  @Test
  void C_the_colors_are_stored_and_the_public_profile_returns_them_and_only_for_that_tenant() {
    String acme = active("acme-tours");
    active("other-tours");
    Response response = set(acme, "#146b5d", "#0f564b");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
    Response shown = profile("acme-tours");
    assertThat(shown.jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
    assertThat(shown.jsonPath().getString("visualIdentity.secondaryColor")).isEqualTo("#0F564B");
    assertThat(profile("other-tours").jsonPath().getMap("$").get("visualIdentity")).isNull();
    assertThat(admin().get(TENANTS + "/" + acme).jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
  }

  @Test
  void C_inactivating_and_reactivating_a_tenant_keeps_its_colors() {
    String id = active("acme-tours");
    set(id, "#146B5D", "#0F564B");
    assertThat(admin().body(Map.of("reason", "unpaid")).post(TENANTS + "/" + id + "/inactivate").statusCode()).isEqualTo(200);
    assertThat(profile("acme-tours").jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
    assertThat(admin().body(Map.of("reason", "paid")).post(TENANTS + "/" + id + "/reactivate").statusCode()).isEqualTo(200);
    assertThat(profile("acme-tours").jsonPath().getString("visualIdentity.secondaryColor")).isEqualTo("#0F564B");
  }

  @Test
  void C_an_inactive_tenant_refuses_the_colors_with_422_and_keeps_what_it_had() {
    String id = active("acme-tours");
    set(id, "#146B5D", "#0F564B");
    admin().body(Map.of("reason", "unpaid")).post(TENANTS + "/" + id + "/inactivate");
    Response refused = administratorOf(id).filter(Contract.answers())
        .body(Map.of("primaryColor", "#000000", "secondaryColor", "#FFFFFF")).put(ROUTE);
    assertThat(refused.statusCode()).isEqualTo(422);
    assertThat(refused.jsonPath().getString("error")).isEqualTo("BUSINESS_RULE_VIOLATION");
    assertThat(profile("acme-tours").jsonPath().getString("visualIdentity.primaryColor")).isEqualTo("#146B5D");
  }
}