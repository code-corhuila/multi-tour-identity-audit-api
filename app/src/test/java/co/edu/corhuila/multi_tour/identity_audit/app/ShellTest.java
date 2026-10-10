package co.edu.corhuila.multi_tour.identity_audit.app;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The service on a random port, given the public half of the key the tests sign their tokens with. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class ShellTest {

  @LocalServerPort int port;

  @DynamicPropertySource
  static void publicKey(DynamicPropertyRegistry registry) {
    registry.add("JWT_PUBLIC_KEY", Tokens::publicKeyPem);
    // the real database where the CI has made it; otherwise an address nobody connects to (the driver is lazy)
    registry.add("MONGODB_URI", () -> {
      String real = System.getenv("TEST_DATABASE_URL");
      return real != null && !real.isBlank() ? real : "mongodb://localhost:27017/identity_audit";
    });
  }

  @BeforeEach
  void aim() {
    RestAssured.port = port;
  }
}
