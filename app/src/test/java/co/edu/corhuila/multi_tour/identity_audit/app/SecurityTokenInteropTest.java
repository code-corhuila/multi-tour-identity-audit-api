package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The token that the security microservice issues for a service (RN-SEC-001-13, task SEC-11 of that piece), made by its Go
 * signer and checked here by this service with only the public key it was given, the issuer and the audience as the
 * deployment names them. It is the proof that two languages agree on the credential.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"JWT_ISSUER=multi-tour-security", "JWT_AUDIENCE=multi-tour"})
class SecurityTokenInteropTest {

  private static final String OUTBOX = "/api/v1/identity-audit/internal/outbox";
  private static final String TENANTS = "/api/v1/identity-audit/tenants";

  @LocalServerPort int port;

  @DynamicPropertySource
  static void securityKey(DynamicPropertyRegistry registry) {
    registry.add("JWT_PUBLIC_KEY", () -> resource("security-public-key.pem").strip().replace("\n", "\\n"));
    registry.add("MONGODB_URI", () -> "mongodb://localhost:27017/identity_audit"); // lazy: no route below reads it
  }

  private static String resource(String name) {
    try (var in = SecurityTokenInteropTest.class.getResourceAsStream("/interop/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("the fixture " + name + " is missing", e);
    }
  }

  private static String serviceToken() {
    return resource("service-token.jwt").strip();
  }

  @BeforeEach
  void aim() {
    RestAssured.port = port;
  }

  @Test
  void C_the_token_of_a_service_issued_by_the_security_microservice_is_accepted_as_a_service() {
    // the request is judged after the token and the role: the 400 is the answer of a service that was let in
    var response = given().header("Authorization", "Bearer " + serviceToken()).get(OUTBOX + "?status=PUBLISHED");
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(400);
    assertThat(response.jsonPath().getString("error")).isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void C_that_token_is_a_service_and_not_a_platform_administrator() {
    var response = given().header("Authorization", "Bearer " + serviceToken()).get(TENANTS);
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(403);
    assertThat(response.jsonPath().getString("error")).isEqualTo("FORBIDDEN");
  }

  @Test
  void C_only_the_key_of_the_security_microservice_is_trusted() {
    // a token signed with the key of the other tests carries the same claims and is refused: the key is the credential
    String other = Tokens.valid(Tokens.claims("worker-event-relay", 600, java.util.List.of("SERVICE"), null)
        .issuer("multi-tour-security").audience("multi-tour"));
    assertThat(given().header("Authorization", "Bearer " + other).get(OUTBOX).statusCode()).isEqualTo(401);
    assertThat(given().get(OUTBOX).statusCode()).isEqualTo(401);
  }
}
