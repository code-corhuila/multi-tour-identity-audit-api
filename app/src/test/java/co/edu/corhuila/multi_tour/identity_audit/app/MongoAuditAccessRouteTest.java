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

/** GET /tenant/audit-access and PUT /tenant/audit-access/{role}: the Tenant Administrator opens the audit to an optional role (HU-IDA-002 AC6). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoAuditAccessRouteTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String OTHER_TENANT = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String ADMIN = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String ROUTE = "/api/v1/identity-audit/tenant/audit-access";

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("audit_access_settings", "audit_records")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims(ADMIN, 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification administrator() {
    return as("TENANT_ADMINISTRATOR", TENANT);
  }

  private static Response give(String role, boolean enabled) {
    return administrator().filter(Contract.filter()).body(Map.of("enabled", enabled)).put(ROUTE + "/" + role);
  }

  private long audits() {
    return client.getDatabase(database).getCollection("audit_records").countDocuments();
  }

  private Document setting(String tenantId, String role) {
    return client.getDatabase(database).getCollection("audit_access_settings")
        .find(new Document("tenantId", tenantId).append("role", role)).first();
  }

  @Test
  void C_only_the_tenant_administrator_changes_the_audit_access() {
    assertThat(given().contentType(ContentType.JSON).filter(Contract.answers()).get(ROUTE).statusCode()).isEqualTo(401);
    assertThat(given().contentType(ContentType.JSON).filter(Contract.answers()).body(Map.of("enabled", true)).put(ROUTE + "/MANAGER").statusCode())
        .isEqualTo(401);

    RequestSpecification platform = given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
    List<RequestSpecification> refused = List.of(
        platform,
        as("SERVICE", null),
        as("MANAGER", TENANT),
        as("ACCOUNTANT", TENANT),
        as("ANALYST", TENANT),
        as("OPERATIONAL_COLLABORATOR", TENANT),
        as("FINAL_CUSTOMER", TENANT));
    for (RequestSpecification who : refused) {
      assertThat(who.filter(Contract.answers()).get(ROUTE).statusCode()).isEqualTo(403);
    }
    for (RequestSpecification who : List.of(
        given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON),
        as("MANAGER", TENANT), as("FINAL_CUSTOMER", TENANT))) {
      assertThat(who.filter(Contract.answers()).body(Map.of("enabled", true)).put(ROUTE + "/ANALYST").statusCode()).isEqualTo(403);
    }
    assertThat(audits()).as("a refused caller writes nothing").isZero();
    assertThat(setting(TENANT, "ANALYST")).isNull();
  }

  @Test
  void C_the_three_roles_are_listed_and_setting_what_is_already_set_writes_nothing() {
    Response none = administrator().filter(Contract.filter()).get(ROUTE);
    assertThat(none.statusCode()).isEqualTo(200);
    assertThat(none.jsonPath().getList("data.role")).containsExactly("MANAGER", "ACCOUNTANT", "ANALYST");
    assertThat(none.jsonPath().getList("data.enabled")).containsOnly(false);

    Response given = give("MANAGER", true);
    assertThat(given.statusCode()).isEqualTo(200);
    assertThat(given.jsonPath().getString("role")).isEqualTo("MANAGER");
    assertThat(given.jsonPath().getBoolean("enabled")).isTrue();
    assertThat(given.jsonPath().getString("changedBy")).isEqualTo(ADMIN);
    assertThat(given.jsonPath().getString("changedAt")).isNotNull();

    assertThat(give("MANAGER", true).statusCode()).as("a repeat is not an error").isEqualTo(200);
    assertThat(give("ANALYST", false).statusCode()).as("what was never set is already off").isEqualTo(200);
    assertThat(audits()).as("only the real change was recorded").isEqualTo(1);

    Response after = administrator().filter(Contract.filter()).get(ROUTE);
    assertThat(after.jsonPath().getList("data.enabled")).containsExactly(true, false, false);
  }

  @Test
  void C_a_role_that_is_not_optional_or_a_bad_body_is_400() {
    for (String role : List.of("FINAL_CUSTOMER", "TENANT_ADMINISTRATOR", "OPERATIONAL_COLLABORATOR", "manager", "NOBODY")) {
      Response answer = administrator().filter(Contract.answers()).body(Map.of("enabled", true)).put(ROUTE + "/" + role);
      assertThat(answer.statusCode()).as("%s: %s", role, answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
      assertThat(answer.asString()).as("the value is not repeated").doesNotContain(role);
    }
    for (Object body : List.of(Map.of(), Map.of("enabled", "yes"), Map.of("enabled", true, "role", "MANAGER"), Map.of("tenantId", OTHER_TENANT, "enabled", true))) {
      Response answer = administrator().filter(Contract.answers()).body(body).put(ROUTE + "/MANAGER");
      assertThat(answer.statusCode()).as("%s: %s", body, answer.asString()).isEqualTo(400);
    }
    assertThat(administrator().filter(Contract.answers()).body("{\"enabled\": ").put(ROUTE + "/MANAGER").statusCode()).isEqualTo(400);
    for (String raw : List.of("null", "", "[]", "\"true\"")) {
      assertThat(administrator().filter(Contract.answers()).body(raw).put(ROUTE + "/MANAGER").statusCode()).as("body [%s]", raw).isEqualTo(400);
    }
    assertThat(audits()).isZero();
  }

  @Test
  void I_an_access_change_and_its_record_are_written_together_and_only_in_the_tenant_of_the_caller() {
    assertThat(give("ANALYST", true).statusCode()).isEqualTo(200);

    Document kept = setting(TENANT, "ANALYST");
    assertThat(kept.getBoolean("enabled")).isTrue();
    assertThat(kept.getString("changedBy")).isEqualTo(ADMIN);
    assertThat(kept.getDate("changedAt")).isNotNull();
    Document record = client.getDatabase(database).getCollection("audit_records").find().first();
    assertThat(record.getString("action")).isEqualTo("AUDIT_ACCESS_ENABLED");
    assertThat(record.getString("tenantId")).isEqualTo(TENANT);
    assertThat(record.getString("actorId")).isEqualTo(ADMIN);
    assertThat(record.get("previousValue", Document.class).getBoolean("enabled")).isFalse();
    assertThat(record.get("newValue", Document.class).getBoolean("enabled")).isTrue();

    assertThat(give("ANALYST", false).statusCode()).isEqualTo(200);
    assertThat(setting(TENANT, "ANALYST").getBoolean("enabled")).isFalse();
    assertThat(audits()).isEqualTo(2);
    assertThat(client.getDatabase(database).getCollection("audit_records").countDocuments(new Document("action", "AUDIT_ACCESS_DISABLED"))).isEqualTo(1);

    Response other = as("TENANT_ADMINISTRATOR", OTHER_TENANT).filter(Contract.filter()).get(ROUTE);
    assertThat(other.jsonPath().getList("data.enabled")).as("another tenant is not touched").containsOnly(false);
    assertThat(setting(OTHER_TENANT, "ANALYST")).isNull();
  }
}
