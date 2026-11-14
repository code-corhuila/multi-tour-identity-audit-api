package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** The audit lists: across tenants for the Platform Administrator, and of one tenant for its users (HU-IDA-002 AC3, AC4, AC5, AC8). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoAuditListRouteTest extends ShellTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String OTHER = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String ADMIN = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String ALL = "/api/v1/identity-audit/audit-records";
  private static final String OF_TENANT = "/api/v1/identity-audit/tenants/" + TENANT + "/audit-records";
  private static final String OWN = "/api/v1/identity-audit/tenant/audit-records";
  private static final String OF_OTHER = "/api/v1/identity-audit/tenants/" + OTHER + "/audit-records";
  private static final String ACCESS = "/api/v1/identity-audit/tenant/audit-access/";
  private static final Instant BASE = Instant.parse("2026-11-06T10:00:00Z");

  @Autowired MongoClient client;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("audit_records", "audit_access_settings")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static RequestSpecification as(String role, String tenantId) {
    String token = Tokens.valid(Tokens.claims(ADMIN, 600, List.of(role), tenantId));
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification platform() {
    return given().header("Authorization", "Bearer " + Tokens.valid(Tokens.admin())).contentType(ContentType.JSON);
  }

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  /** The record n, n minutes after BASE; a null tenant is an action of the platform. */
  private void seed(int n, String tenantId, String action) {
    Document record = new Document("_id", id(n)).append("eventId", id(1000 + n));
    if (tenantId != null) {
      record.append("tenantId", tenantId);
    }
    record.append("actorId", ADMIN).append("action", action).append("affectedRecordId", "accounts/" + id(n))
        .append("recordedAt", Date.from(BASE.plusSeconds(60L * n)));
    client.getDatabase(database).getCollection("audit_records").insertOne(record);
  }

  private static Response read(RequestSpecification who, String path) {
    return who.filter(Contract.filter()).get(path);
  }

  private static Response refused(RequestSpecification who, String path) {
    return who.filter(Contract.answers()).get(path);
  }

  @Test
  void C_only_the_platform_administrator_reads_the_audit_across_tenants() {
    seed(1, TENANT, "ACCOUNT_BLOCKED");
    seed(2, OTHER, "TENANT_INACTIVATED");
    seed(3, null, "ACCOUNT_BLOCKED");

    assertThat(given().filter(Contract.answers()).get(ALL).statusCode()).isEqualTo(401);
    for (RequestSpecification who : List.of(
        as("TENANT_ADMINISTRATOR", TENANT), as("MANAGER", TENANT), as("ANALYST", TENANT),
        as("OPERATIONAL_COLLABORATOR", TENANT), as("FINAL_CUSTOMER", TENANT), as("SERVICE", null))) {
      assertThat(refused(who, ALL).statusCode()).isEqualTo(403);
    }

    Response everything = read(platform(), ALL);
    assertThat(everything.statusCode()).isEqualTo(200);
    assertThat(everything.jsonPath().getList("data.auditRecordId")).as("every tenant and the platform, newest first")
        .containsExactly(id(3), id(2), id(1));
    assertThat(everything.jsonPath().getInt("meta.total")).isEqualTo(3);

    Response one = read(platform(), ALL + "?tenantId=" + OTHER);
    assertThat(one.jsonPath().getList("data.auditRecordId")).containsExactly(id(2));
    assertThat(read(platform(), ALL + "?tenantId=" + TENANT + "&action=TENANT_INACTIVATED").jsonPath().getList("data")).isEmpty();
  }

  @Test
  void C_a_tenant_audit_is_read_by_its_administrator_and_by_an_enabled_role_only() {
    seed(1, TENANT, "ACCOUNT_BLOCKED");
    seed(2, OTHER, "ACCOUNT_BLOCKED");
    seed(3, TENANT, "SPECIAL_PERMISSION_GRANTED");
    seed(4, null, "ACCOUNT_BLOCKED");

    Response administrator = read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT);
    assertThat(administrator.statusCode()).isEqualTo(200);
    assertThat(administrator.jsonPath().getList("data.auditRecordId")).as("only the records of the tenant").containsExactly(id(3), id(1));
    assertThat(administrator.jsonPath().getString("data[0].affectedRecordId")).isEqualTo("accounts/" + id(3));

    for (String role : List.of("MANAGER", "ACCOUNTANT", "ANALYST", "OPERATIONAL_COLLABORATOR", "FINAL_CUSTOMER")) {
      assertThat(refused(as(role, TENANT), OF_TENANT).statusCode()).as("%s without the access", role).isEqualTo(403);
    }
    assertThat(refused(platform(), OF_TENANT).statusCode()).as("the Platform Administrator uses its own route").isEqualTo(403);
    assertThat(refused(as("SERVICE", null), OF_TENANT).statusCode()).isEqualTo(403);

    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", true)).put(ACCESS + "ANALYST").statusCode()).isEqualTo(200);

    Response analyst = read(as("ANALYST", TENANT), OF_TENANT);
    assertThat(analyst.jsonPath().getList("data.auditRecordId")).hasSize(3).endsWith(id(3), id(1));
    assertThat(analyst.jsonPath().getString("data[0].action")).as("opening the access left its own record, the newest").isEqualTo("AUDIT_ACCESS_ENABLED");
    assertThat(analyst.jsonPath().getString("data[0].actorId")).isEqualTo(ADMIN);
    assertThat(refused(as("MANAGER", TENANT), OF_TENANT).statusCode()).as("another role of the tenant still has no access").isEqualTo(403);
    assertThat(refused(as("OPERATIONAL_COLLABORATOR", TENANT), OF_TENANT).statusCode()).isEqualTo(403);
    assertThat(refused(as("FINAL_CUSTOMER", TENANT), OF_TENANT).statusCode()).isEqualTo(403);

    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", false)).put(ACCESS + "ANALYST").statusCode()).isEqualTo(200);
    assertThat(refused(as("ANALYST", TENANT), OF_TENANT).statusCode()).as("closing the access closes it at once").isEqualTo(403);
  }

  @Test
  void C_filter_by_a_support_access_action() {
    seed(1, TENANT, "PLATFORM_EXCEPTIONAL_ACCESS_OPENED");
    seed(2, TENANT, "ACCOUNT_BLOCKED");
    seed(3, TENANT, "PLATFORM_EXCEPTIONAL_ACCESS_CLOSED");

    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?action=PLATFORM_EXCEPTIONAL_ACCESS_OPENED").jsonPath().getList("data.auditRecordId"))
        .containsExactly(id(1));
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?action=PLATFORM_EXCEPTIONAL_ACCESS_CLOSED").jsonPath().getList("data.auditRecordId"))
        .containsExactly(id(3));
    assertThat(refused(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?action=PLATFORM_EXCEPTIONAL_ACCESS_NOPE").statusCode()).isEqualTo(400);
  }

  @Test
  void C_another_tenants_audit_is_404_whatever_the_role_and_the_access() {
    seed(1, OTHER, "ACCOUNT_BLOCKED");
    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", true)).put(ACCESS + "MANAGER").statusCode()).isEqualTo(200);
    // the route exists and answers its own tenant, so the 404 below is the rule and not a route that is missing
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT).statusCode()).isEqualTo(200);

    for (String role : List.of("TENANT_ADMINISTRATOR", "MANAGER", "ANALYST", "OPERATIONAL_COLLABORATOR", "FINAL_CUSTOMER")) {
      Response answer = refused(as(role, TENANT), OF_OTHER);
      assertThat(answer.statusCode()).as("%s: %s", role, answer.asString()).isEqualTo(404);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("NOT_FOUND");
      assertThat(answer.asString()).as("nothing of the other tenant is told").doesNotContain(id(1));
    }
    // a query that is malformed is 400 whoever asks and whatever tenant it names: it tells nothing about the audit
    assertThat(refused(as("FINAL_CUSTOMER", TENANT), OF_OTHER + "?limit=0").statusCode()).isEqualTo(400);
    assertThat(refused(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?limit=0").statusCode()).isEqualTo(400);
  }

  @Test
  void C_the_audit_lists_page_and_filter_as_the_contract_says() {
    for (int n = 1; n <= 25; n++) {
      seed(n, TENANT, n % 5 == 0 ? "ACCOUNT_BLOCKED" : "SPECIAL_PERMISSION_GRANTED");
    }
    RequestSpecification who = as("TENANT_ADMINISTRATOR", TENANT);

    Response first = read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT);
    assertThat(first.jsonPath().getList("data")).as("20 by default").hasSize(20);
    assertThat(first.jsonPath().getInt("meta.total")).isEqualTo(25);
    assertThat(first.jsonPath().getInt("meta.totalPages")).isEqualTo(2);

    Response page3 = read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?page=3&limit=10");
    assertThat(page3.jsonPath().getList("data.auditRecordId")).containsExactly(id(5), id(4), id(3), id(2), id(1));
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?page=9&limit=10").jsonPath().getList("data")).isEmpty();

    String from = BASE.plusSeconds(60L * 5).toString();
    String to = BASE.plusSeconds(60L * 10).toString();
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?from=" + from + "&to=" + to).jsonPath().getList("data.auditRecordId"))
        .as("from is inclusive and to is exclusive").containsExactly(id(9), id(8), id(7), id(6), id(5));
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?action=ACCOUNT_BLOCKED&limit=2").jsonPath().getList("data.auditRecordId"))
        .containsExactly(id(25), id(20));
    Response combined = read(as("TENANT_ADMINISTRATOR", TENANT), OF_TENANT + "?from=" + from + "&to=" + to + "&action=ACCOUNT_BLOCKED");
    assertThat(combined.jsonPath().getList("data.auditRecordId")).as("period and action together").containsExactly(id(5));
    assertThat(combined.jsonPath().getInt("meta.total")).isEqualTo(1);

    for (String bad : List.of("?page=0", "?limit=0", "?limit=101", "?limit=abc", "?page=-1", "?from=yesterday", "?to=2026-13-45T00:00:00Z",
        "?from=" + to + "&to=" + from, "?action=NOPE", "?action=account_blocked", "?nickname=x", "?page=1&page=2",
        "?tenantId=" + OTHER, "?from=%2B1000000000-01-01T00:00:00Z", "?from=2026-11-06T10:00:00%2B01:00", "?from=2026-11-06t10:00:00z",
        "?from=", "?action=", "?page=")) {
      Response answer = refused(who, OF_TENANT + bad);
      assertThat(answer.statusCode()).as("%s: %s", bad, answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(refused(platform(), ALL + "?action=NOPE").statusCode()).isEqualTo(400);
    assertThat(refused(platform(), ALL + "?tenantId=not-a-uuid").statusCode()).isEqualTo(400);
    assertThat(refused(platform(), ALL + "?limit=101").statusCode()).isEqualTo(400);
  }

  @Test
  void C_the_own_tenant_audit_never_takes_the_tenant_from_the_request() {
    seed(1, TENANT, "ACCOUNT_BLOCKED");
    seed(2, OTHER, "ACCOUNT_BLOCKED");
    seed(3, null, "ACCOUNT_BLOCKED");

    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OWN).jsonPath().getList("data.auditRecordId")).containsExactly(id(1));
    assertThat(read(as("TENANT_ADMINISTRATOR", OTHER), OWN).jsonPath().getList("data.auditRecordId")).as("each token reads its own").containsExactly(id(2));
    assertThat(refused(as("TENANT_ADMINISTRATOR", TENANT), OWN + "?tenantId=" + OTHER).statusCode()).as("a tenant in the query").isEqualTo(400);
    assertThat(refused(as("TENANT_ADMINISTRATOR", TENANT), OWN + "?tenantId=" + TENANT).statusCode()).as("not even its own").isEqualTo(400);
    Response header = as("TENANT_ADMINISTRATOR", TENANT).header("X-Tenant-Id", OTHER).filter(Contract.filter()).get(OWN);
    assertThat(header.jsonPath().getList("data.auditRecordId")).as("a header names nothing").containsExactly(id(1));
  }

  @Test
  void C_the_own_tenant_audit_is_read_by_its_administrator_and_by_an_enabled_role_and_refused_to_the_rest() {
    seed(1, TENANT, "ACCOUNT_BLOCKED");
    seed(2, OTHER, "ACCOUNT_BLOCKED");

    Response administrator = read(as("TENANT_ADMINISTRATOR", TENANT), OWN);
    assertThat(administrator.statusCode()).isEqualTo(200);
    assertThat(administrator.jsonPath().getList("data.auditRecordId")).containsExactly(id(1));

    for (String role : List.of("MANAGER", "ACCOUNTANT", "ANALYST", "OPERATIONAL_COLLABORATOR", "FINAL_CUSTOMER")) {
      assertThat(refused(as(role, TENANT), OWN).statusCode()).as("%s without the access", role).isEqualTo(403);
    }
    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", true)).put(ACCESS + "MANAGER").statusCode()).isEqualTo(200);
    assertThat(read(as("MANAGER", TENANT), OWN).statusCode()).isEqualTo(200);
    assertThat(refused(as("ANALYST", TENANT), OWN).statusCode()).as("another role still has no access").isEqualTo(403);
    assertThat(refused(as("OPERATIONAL_COLLABORATOR", TENANT), OWN).statusCode()).isEqualTo(403);
    assertThat(refused(as("FINAL_CUSTOMER", TENANT), OWN).statusCode()).isEqualTo(403);

    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", false)).put(ACCESS + "MANAGER").statusCode()).isEqualTo(200);
    assertThat(refused(as("MANAGER", TENANT), OWN).statusCode()).as("closing the access closes it at once").isEqualTo(403);
  }

  @Test
  void C_the_own_tenant_audit_is_refused_to_a_token_with_no_tenant() {
    seed(1, TENANT, "ACCOUNT_BLOCKED");
    seed(2, null, "ACCOUNT_BLOCKED");

    assertThat(given().filter(Contract.answers()).get(OWN).statusCode()).isEqualTo(401);
    assertThat(refused(platform(), OWN).statusCode()).as("the Platform Administrator uses its own route").isEqualTo(403);
    assertThat(refused(as("SERVICE", null), OWN).statusCode()).isEqualTo(403);
    for (String role : List.of("TENANT_ADMINISTRATOR", "MANAGER")) {
      assertThat(refused(as(role, null), OWN).statusCode()).as("%s with a token that names no tenant", role).isEqualTo(403);
    }
  }

  @Test
  void C_the_own_tenant_audit_pages_and_filters_as_the_contract_says() {
    for (int n = 1; n <= 25; n++) {
      seed(n, TENANT, n % 5 == 0 ? "ACCOUNT_BLOCKED" : "SPECIAL_PERMISSION_GRANTED");
    }
    RequestSpecification who = as("TENANT_ADMINISTRATOR", TENANT);

    Response first = read(as("TENANT_ADMINISTRATOR", TENANT), OWN);
    assertThat(first.jsonPath().getList("data")).as("20 by default").hasSize(20);
    assertThat(first.jsonPath().getInt("meta.total")).isEqualTo(25);
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OWN + "?page=3&limit=10").jsonPath().getList("data.auditRecordId"))
        .containsExactly(id(5), id(4), id(3), id(2), id(1));
    String from = BASE.plusSeconds(60L * 5).toString();
    String to = BASE.plusSeconds(60L * 10).toString();
    assertThat(read(as("TENANT_ADMINISTRATOR", TENANT), OWN + "?from=" + from + "&to=" + to + "&action=ACCOUNT_BLOCKED")
        .jsonPath().getList("data.auditRecordId")).as("period and action together").containsExactly(id(5));

    for (String bad : List.of("?page=0", "?limit=0", "?limit=101", "?limit=abc", "?from=yesterday", "?from=" + to + "&to=" + from,
        "?action=NOPE", "?nickname=x", "?page=1&page=2", "?from=", "?action=")) {
      Response answer = refused(who, OWN + bad);
      assertThat(answer.statusCode()).as("%s: %s", bad, answer.asString()).isEqualTo(400);
      assertThat(answer.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  void C_both_audit_routes_of_a_tenant_answer_the_same_records() {
    for (int n = 1; n <= 12; n++) {
      seed(n, n % 4 == 0 ? OTHER : TENANT, n % 3 == 0 ? "ACCOUNT_BLOCKED" : "SPECIAL_PERMISSION_GRANTED");
    }
    assertThat(as("TENANT_ADMINISTRATOR", TENANT).body(Map.of("enabled", true)).put(ACCESS + "ANALYST").statusCode()).isEqualTo(200);

    for (String role : List.of("TENANT_ADMINISTRATOR", "ANALYST")) {
      for (String query : List.of("", "?limit=3&page=2", "?action=ACCOUNT_BLOCKED", "?from=" + BASE.plusSeconds(180) + "&to=" + BASE.plusSeconds(600))) {
        Response own = read(as(role, TENANT), OWN + query);
        Response ofTenant = read(as(role, TENANT), OF_TENANT + query);
        assertThat(own.asString()).as("%s on %s", role, query).isEqualTo(ofTenant.asString());
      }
    }
  }
}
