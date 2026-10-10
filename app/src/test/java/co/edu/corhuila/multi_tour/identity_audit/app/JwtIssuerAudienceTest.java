package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** When the operator names the issuer and the audience of the tokens, a token without them is refused. */
@TestPropertySource(properties = {"JWT_ISSUER=multi-tour-security", "JWT_AUDIENCE=multi-tour"})
class JwtIssuerAudienceTest extends ShellTest {

  private static final String PROTECTED = "/api/v1/identity-audit/tenants/not-built/audit-records";

  private static JWTClaimsSet.Builder token() {
    return Tokens.claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), null);
  }

  private static int status(JWTClaimsSet.Builder claims) {
    return given().header("Authorization", "Bearer " + Tokens.valid(claims)).get(PROTECTED).statusCode();
  }

  @Test
  void C_a_token_of_the_configured_issuer_and_audience_passes_the_filter() {
    assertThat(status(token().issuer("multi-tour-security").audience("multi-tour"))).isEqualTo(404);
    assertThat(status(token().issuer("multi-tour-security").audience(List.of("other", "multi-tour")))).isEqualTo(404);
  }

  @Test
  void C_a_token_of_another_issuer_or_without_one_is_a_401() {
    assertThat(status(token().issuer("someone-else").audience("multi-tour"))).isEqualTo(401);
    assertThat(status(token().audience("multi-tour"))).isEqualTo(401);
  }

  @Test
  void C_a_token_of_another_audience_or_without_one_is_a_401() {
    assertThat(status(token().issuer("multi-tour-security").audience("another"))).isEqualTo(401);
    assertThat(status(token().issuer("multi-tour-security"))).isEqualTo(401);
  }
}
