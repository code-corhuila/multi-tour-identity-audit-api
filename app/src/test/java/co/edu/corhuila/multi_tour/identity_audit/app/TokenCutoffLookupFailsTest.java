package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoff;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoffs;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** When the cutoff of the account cannot be read, the token is not let through (ADR-004 D5: a revocation is never skipped). */
@Import(TokenCutoffLookupFailsTest.FailingCutoffs.class)
class TokenCutoffLookupFailsTest extends ShellTest {

  @TestConfiguration
  static class FailingCutoffs {
    @Bean
    @Primary
    SessionCutoffs failing() {
      return new SessionCutoffs() {
        @Override
        public Optional<SessionCutoff> find(String accountId) {
          throw new IllegalStateException("the database does not answer");
        }

        @Override
        public boolean record(String eventId, SessionCutoff expected, SessionCutoff next, Instant now) {
          throw new IllegalStateException("the database does not answer");
        }
      };
    }
  }

  @Test
  void C_a_lookup_that_fails_never_lets_the_token_through() {
    String token = Tokens.valid(Tokens.claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), null));

    Response answer = given().header("Authorization", "Bearer " + token).get("/api/v1/identity-audit/tenants/not-built/audit-records");

    assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(500);
    assertThat(answer.jsonPath().getString("error")).isEqualTo("INTERNAL_ERROR");
    assertThat(answer.asString()).as("no detail of the fault reaches the caller").doesNotContain("does not answer");
  }
}
