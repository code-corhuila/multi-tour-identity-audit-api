package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** GET /health with the real database: it says so, and the contract agrees. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoHealthRouteTest extends ShellTest {

  @Test
  void C_health_shows_the_database_up() {
    Response response = given().filter(Contract.filter()).get("/health");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("status")).isEqualTo("ok");
    assertThat(response.jsonPath().getString("dependencies.database")).isEqualTo("ok");
    assertThat(response.jsonPath().getString("timestamp")).isNotBlank();
  }
}
