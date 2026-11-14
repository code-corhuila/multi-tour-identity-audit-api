package co.edu.corhuila.multi_tour.identity_audit.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the example of the environment tells an operator to write is what the security microservice puts in its tokens. */
class EnvExampleTest {

  private static List<String> lines() throws IOException {
    // the tests of this module run from its folder; the example is at the root of the repository
    Path example = Path.of("..", ".env.example");
    return Files.readAllLines(example);
  }

  @Test
  void C_the_example_names_the_issuer_and_the_audience_the_security_microservice_signs_with() throws IOException {
    assertThat(lines()).contains("JWT_ISSUER=multi-tour-security", "JWT_AUDIENCE=multi-tour");
  }

  @Test
  void C_the_example_holds_no_key_and_no_secret() throws IOException {
    assertThat(lines()).filteredOn(line -> line.startsWith("JWT_PUBLIC_KEY=")).containsExactly("JWT_PUBLIC_KEY=");
    assertThat(String.join("\n", lines())).doesNotContain("BEGIN").doesNotContain("password");
  }

  // Spec 010 (HU-IDA-004): the zone changes only after reading the cash boxes of another service, with the token of this one.
  @Test
  void C_the_example_and_the_compose_name_the_cash_reporting_address_and_the_service_token_without_a_value() throws IOException {
    assertThat(lines()).contains("CASH_REPORTING_BASE_URL=", "SERVICE_TOKEN=");
    String compose = String.join(System.lineSeparator(), Files.readAllLines(Path.of("..", "deploy", "compose.yml")));
    assertThat(compose).contains("CASH_REPORTING_BASE_URL: ${CASH_REPORTING_BASE_URL:-}").contains("SERVICE_TOKEN: ${SERVICE_TOKEN:-}");
  }
}
