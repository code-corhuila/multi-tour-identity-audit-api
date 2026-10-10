package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/** What a failure of the core becomes on the wire: one status, one code, one place that decides it. */
class DomainErrorsTest extends ShellTest {

  private Response failure(String kind) {
    return given().get("/probe/domain/" + kind);
  }

  private void assertEnvelope(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_data_the_core_refuses_is_a_400_validation_error_with_its_message() {
    Response response = failure("invalid-data");
    assertEnvelope(response, 400, "VALIDATION_ERROR");
    assertThat(response.jsonPath().getString("message")).isEqualTo("The identifier is 3 to 40 lowercase letters");
  }

  @Test
  void C_an_unknown_tenant_is_a_404() {
    assertEnvelope(failure("not-found"), 404, "NOT_FOUND");
  }

  @Test
  void C_a_live_identifier_is_a_422_business_rule_violation_that_names_it() {
    Response response = failure("identifier-taken");
    assertEnvelope(response, 422, "BUSINESS_RULE_VIOLATION");
    assertThat(response.jsonPath().getString("message")).contains("acme-tours");
    assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("identifier");
  }

  @Test
  void C_a_refused_transition_is_a_422_invalid_status_transition_apart_from_the_business_rule() {
    assertEnvelope(failure("invalid-transition"), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_a_change_that_raced_is_a_422_that_says_to_try_again_and_not_a_server_error() {
    Response response = failure("concurrent");
    assertEnvelope(response, 422, "BUSINESS_RULE_VIOLATION");
    assertThat(response.jsonPath().getString("message")).contains("try again");
  }

  @Test
  void C_an_illegal_argument_that_is_not_the_core_refusing_data_is_a_500_that_shows_nothing() {
    Response response = failure("plain-illegal-argument");
    assertEnvelope(response, 500, "INTERNAL_ERROR");
    assertThat(response.asString()).doesNotContain("secret").doesNotContain("mongodb");
  }
}
