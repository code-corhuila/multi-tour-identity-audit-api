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

/** Step 3 of the saga and its compensation: complete-onboarding and fail-onboarding, against the real database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoOnboardingRoutesTest extends ShellTest {

  private static final String BASE = "/api/v1/identity-audit/internal/tenants";
  private static final String ADMIN = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String OTHER_ADMIN = "7a9d1c3e-2b4f-4c6d-8e0a-1f3b5d7c9e22";
  private static final String UNKNOWN = "11111111-2222-4333-8444-555555555555";

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

  private static String service() {
    return Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
  }

  private static RequestSpecification as(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String pending(String identifier) {
    Map<String, Object> body = Map.of(
        "identifier", identifier, "commercialName", "Acme Tours", "timeZone", "America/Bogota", "country", "CO");
    return as(service()).header("Idempotency-Key", "onboard:" + identifier).body(body).post(BASE)
        .jsonPath().getString("tenantId");
  }

  private static String pending() {
    return pending("acme-tours");
  }

  private static Map<String, Object> completion() {
    Map<String, Object> body = new HashMap<>();
    body.put("firstAdministratorMembershipId", ADMIN);
    body.put("initialStatus", "ACTIVE");
    return body;
  }

  private static Map<String, Object> completionWith(String field, Object value) {
    Map<String, Object> body = completion();
    body.put(field, value);
    return body;
  }

  private static Response complete(String id, Object body) {
    return as(service()).filter(Contract.filter()).body(body).post(BASE + "/" + id + "/complete-onboarding");
  }

  private static Response completeRefused(String id, Object body) {
    return as(service()).filter(Contract.answers()).body(body).post(BASE + "/" + id + "/complete-onboarding");
  }

  private static Response fail(String id, String step) {
    return as(service()).filter(Contract.filter()).body(Map.of("failedStep", step))
        .post(BASE + "/" + id + "/fail-onboarding");
  }

  private static Response failRefused(String id, Object body) {
    return as(service()).filter(Contract.answers()).body(body).post(BASE + "/" + id + "/fail-onboarding");
  }

  private static void assertRefused(Response response, int status, String code) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
  }

  @Test
  void C_completing_a_pending_tenant_applies_the_chosen_status_and_writes_one_event_and_a_repeat_is_a_200() {
    String id = pending();
    Response done = complete(id, completion());
    assertThat(done.statusCode()).isEqualTo(200);
    assertThat(done.jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
    assertThat(done.jsonPath().getString("firstAdministratorMembershipId")).isEqualTo(ADMIN);
    assertThat(count("outbox_events")).isEqualTo(1);
    Response again = complete(id, completion());
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("updatedAt")).isEqualTo(done.jsonPath().getString("updatedAt"));
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void C_a_tenant_can_start_inactive() {
    Response done = complete(pending(), completionWith("initialStatus", "INACTIVE"));
    assertThat(done.jsonPath().getString("tenantStatus")).isEqualTo("INACTIVE");
  }

  @Test
  void C_each_field_of_the_completion_that_is_missing_null_or_invalid_is_a_400_and_nothing_changes() {
    String id = pending();
    for (String field : List.of("firstAdministratorMembershipId", "initialStatus")) {
      Map<String, Object> missing = completion();
      missing.remove(field);
      assertRefused(completeRefused(id, missing), 400, "VALIDATION_ERROR");
      assertRefused(completeRefused(id, completionWith(field, null)), 400, "VALIDATION_ERROR");
    }
    List<Map<String, Object>> invalid = List.of(
        completionWith("firstAdministratorMembershipId", "not-a-uuid"),
        completionWith("firstAdministratorMembershipId", ADMIN.toUpperCase()),
        completionWith("firstAdministratorMembershipId", 7),
        completionWith("initialStatus", "PENDING"), completionWith("initialStatus", "FAILED"),
        completionWith("initialStatus", "active"), completionWith("initialStatus", 1),
        completionWith("extra", true));
    for (Map<String, Object> bad : invalid) {
      Response response = completeRefused(id, bad);
      assertThat(response.statusCode()).as("body %s answered %s", bad, response.asString()).isEqualTo(400);
      assertThat(response.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
    }
    assertRefused(completeRefused(id, "{not json"), 400, "VALIDATION_ERROR");
    assertRefused(completeRefused("not-a-uuid", completion()), 400, "VALIDATION_ERROR");
    assertThat(count("outbox_events")).isZero();
    assertThat(complete(id, completion()).jsonPath().getString("tenantStatus")).isEqualTo("ACTIVE");
  }

  @Test
  void C_another_administrator_on_a_completed_tenant_or_a_completion_of_a_failed_one_is_a_422() {
    String id = pending();
    complete(id, completion());
    assertRefused(completeRefused(id, completionWith("firstAdministratorMembershipId", OTHER_ADMIN)), 422,
        "INVALID_STATUS_TRANSITION");
    String failed = pending("second-tours");
    fail(failed, "CREATE_ACCOUNT");
    assertRefused(completeRefused(failed, completion()), 422, "INVALID_STATUS_TRANSITION");
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void C_failing_a_pending_tenant_marks_it_failed_with_the_step_and_frees_the_identifier() {
    String id = pending();
    Response failed = fail(id, "CREATE_ACCOUNT");
    assertThat(failed.statusCode()).isEqualTo(200);
    assertThat(failed.jsonPath().getString("tenantStatus")).isEqualTo("FAILED");
    assertThat(failed.jsonPath().getString("failedStep")).isEqualTo("CREATE_ACCOUNT");
    assertThat(count("outbox_events")).isZero();
    assertThat(pending("acme-tours")).isNotEqualTo(id);
  }

  @Test
  void C_the_same_step_again_is_a_200_and_another_step_or_a_tenant_already_complete_is_a_422() {
    String id = pending();
    String first = fail(id, "APPLY_STATUS").jsonPath().getString("updatedAt");
    Response again = fail(id, "APPLY_STATUS");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("updatedAt")).isEqualTo(first);
    assertRefused(failRefused(id, Map.of("failedStep", "CREATE_ACCOUNT")), 422, "INVALID_STATUS_TRANSITION");
    String done = pending("done-tours");
    complete(done, completion());
    assertRefused(failRefused(done, Map.of("failedStep", "CREATE_ACCOUNT")), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_the_failed_step_that_is_missing_null_or_invalid_is_a_400() {
    String id = pending();
    Map<String, Object> nulled = new HashMap<>();
    nulled.put("failedStep", null);
    for (Object bad : List.of(Map.of(), nulled, Map.of("failedStep", "OTHER"), Map.of("failedStep", "create_account"),
        Map.of("failedStep", 1), Map.of("failedStep", "CREATE_ACCOUNT", "extra", 1), "{not json")) {
      assertRefused(failRefused(id, bad), 400, "VALIDATION_ERROR");
    }
    assertRefused(failRefused("not-a-uuid", Map.of("failedStep", "CREATE_ACCOUNT")), 400, "VALIDATION_ERROR");
    assertThat(fail(id, "CREATE_ACCOUNT").statusCode()).isEqualTo(200);
  }

  @Test
  void C_a_tenant_that_does_not_exist_is_a_404_with_the_fixed_message_on_both_routes() {
    for (Response response : List.of(
        completeRefused(UNKNOWN, completion()), failRefused(UNKNOWN, Map.of("failedStep", "CREATE_ACCOUNT")))) {
      assertRefused(response, 404, "NOT_FOUND");
      assertThat(response.jsonPath().getString("message")).isEqualTo("There is no such tenant");
    }
  }

  @Test
  void C_without_a_token_a_401_and_with_a_person_token_a_403_judged_before_the_body_on_both_routes() {
    String id = pending();
    String person = Tokens.valid(Tokens.admin());
    for (String route : List.of("complete-onboarding", "fail-onboarding")) {
      String path = BASE + "/" + id + "/" + route;
      Response none = given().filter(Contract.answers()).contentType(ContentType.JSON).body(completion()).post(path);
      assertRefused(none, 401, "UNAUTHORIZED");
      assertRefused(as(person).filter(Contract.answers()).body(completion()).post(path), 403, "FORBIDDEN");
      assertRefused(as(person).filter(Contract.answers()).body("{not json").post(path), 403, "FORBIDDEN");
    }
    assertThat(fail(id, "CREATE_ACCOUNT").jsonPath().getString("tenantStatus")).isEqualTo("FAILED");
  }

  @Test
  void C_a_body_that_is_not_json_is_a_415_on_both_routes() {
    String id = pending();
    for (String route : List.of("complete-onboarding", "fail-onboarding")) {
      Response response = as(service()).filter(Contract.answers()).contentType("text/plain").body("x")
          .post(BASE + "/" + id + "/" + route);
      assertRefused(response, 415, "UNSUPPORTED_MEDIA_TYPE");
    }
  }
}
