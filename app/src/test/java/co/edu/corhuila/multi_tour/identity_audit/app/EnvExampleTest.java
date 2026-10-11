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
}
