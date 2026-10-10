package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class JwtFilterTest extends ShellTest {

  /** A protected route that is not built, so passing the filter shows as the 404 of an unknown route. */
  private static final String PROTECTED = "/api/v1/identity-audit/tenants/not-built/audit-records";

  private Response call(String authorization) {
    var request = given();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return request.get(PROTECTED);
  }

  private void assertRefused(String authorization) {
    Response response = call(authorization);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.jsonPath().getString("error")).isEqualTo("UNAUTHORIZED");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
  }

  @Test
  void C_a_protected_route_without_a_token_is_a_401_with_the_envelope() {
    assertRefused(null);
    assertRefused("Basic dXNlcjpwYXNz");
    assertRefused("Bearer ");
    assertRefused("Bearer abc.def.ghi");
  }

  @Test
  void C_a_valid_token_passes_the_filter() {
    // the route is not built yet, so passing the filter shows as the 404 of an unknown route
    assertThat(call("Bearer " + Tokens.valid(Tokens.admin())).statusCode()).isEqualTo(404);
  }

  @Test
  void C_tokens_that_are_not_rs256_signed_by_the_security_key_are_refused() {
    JWTClaimsSet claims = Tokens.admin().build();
    assertRefused("Bearer " + Tokens.unsigned(claims));
    assertRefused("Bearer " + Tokens.hs256WithThePublicKey(claims));
    assertRefused("Bearer " + Tokens.rs256(Tokens.OTHER.getPrivate(), claims));
  }

  @Test
  void C_a_token_without_subject_or_expiry_or_expired_is_refused() {
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().subject(null)));
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().expirationTime(null)));
    assertRefused("Bearer " + Tokens.valid(Tokens.claims("account-1", -31, List.of("PLATFORM_ADMINISTRATOR"), null)));
  }

  @Test
  void C_an_expiry_inside_the_leeway_of_thirty_seconds_is_still_accepted() {
    String justExpired = Tokens.valid(Tokens.claims("account-1", -25, List.of("PLATFORM_ADMINISTRATOR"), null));
    assertThat(call("Bearer " + justExpired).statusCode()).isEqualTo(404);
  }

  @Test
  void C_a_token_whose_roles_make_no_sense_is_refused() {
    assertRefused("Bearer " + Tokens.valid(Tokens.claims("account-1", 600, List.of(), null)));
    assertRefused("Bearer " + Tokens.valid(Tokens.claims("account-1", 600, List.of("EVERYTHING"), null)));
    // the Platform Administrator belongs to no tenant (plan D-03)
    assertRefused("Bearer " + Tokens.valid(Tokens.claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), "tenant-1")));
  }

  @Test
  void C_a_signed_token_with_odd_roles_or_subject_is_a_401_and_never_a_server_error() {
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().claim("roles", Arrays.asList((String) null))));
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().claim("roles", Arrays.asList("SERVICE", null))));
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().claim("roles", "PLATFORM_ADMINISTRATOR")));
    assertRefused("Bearer " + Tokens.valid(Tokens.admin().subject("  ")));
  }

  @Test
  void C_the_scheme_is_not_case_sensitive() {
    assertThat(call("bearer " + Tokens.valid(Tokens.admin())).statusCode()).isEqualTo(404);
  }

  @Test
  void C_the_health_route_and_the_public_profile_need_no_token() {
    assertThat(given().get("/health").statusCode()).isEqualTo(200);
    assertThat(given().get("/api/v1/identity-audit/public/tenants/never-registered").statusCode()).isEqualTo(404);
  }

  private String bearer(com.nimbusds.jwt.JWTClaimsSet.Builder claims) {
    return "Bearer " + Tokens.valid(claims);
  }

  @Test
  void C_a_subject_that_is_blank_padded_too_long_or_with_a_control_character_is_a_401_and_100_characters_pass() {
    for (String bad : List.of("", " ", " account-1", "account-1 ", "a".repeat(101), "acc\u0007ount", "acc\u202eount", "acc\u2028ount")) {
      assertRefused(bearer(Tokens.claims(bad, 600, List.of("PLATFORM_ADMINISTRATOR"), null)));
    }
    assertThat(call(bearer(Tokens.claims("a".repeat(100), 600, List.of("PLATFORM_ADMINISTRATOR"), null))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_a_token_that_is_not_valid_yet_beyond_the_tolerance_is_a_401_and_inside_it_passes() {
    var admin = List.of("PLATFORM_ADMINISTRATOR");
    long now = System.currentTimeMillis();
    assertRefused(bearer(Tokens.claims("account-1", 600, admin, null).notBeforeTime(new java.util.Date(now + 120_000))));
    assertThat(call(bearer(Tokens.claims("account-1", 600, admin, null).notBeforeTime(new java.util.Date(now + 10_000)))).statusCode()).isEqualTo(404);
    assertThat(call(bearer(Tokens.claims("account-1", 600, admin, null).notBeforeTime(new java.util.Date(now - 60_000)))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_without_an_issuer_or_audience_configured_those_claims_are_not_read() {
    var claims = Tokens.claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), null).issuer("anyone").audience("anywhere");
    assertThat(call(bearer(claims)).statusCode()).isEqualTo(404);
  }

  @Test
  void C_the_service_does_not_start_without_a_public_key() {
    assertThatThrownBy(() -> start(Map.of()))
        .hasStackTraceContaining("JWT_PUBLIC_KEY");
  }

  @Test
  void C_the_service_starts_with_the_key_in_a_file() throws IOException {
    Path file = Files.createTempFile("jwt", ".pem");
    try {
      Files.writeString(file, Tokens.publicKeyPem().replace("\\n", "\n"));
      try (ConfigurableApplicationContext context = start(Map.of("JWT_PUBLIC_KEY_FILE", file.toString()))) {
        assertThat(context.isRunning()).isTrue();
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void C_the_service_does_not_start_with_a_key_shorter_than_2048_bits() throws Exception {
    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    String body = java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    Path file = Files.createTempFile("short", ".pem");
    try {
      Files.writeString(file, pem);
      assertThatThrownBy(() -> start(Map.of("JWT_PUBLIC_KEY_FILE", file.toString()))).hasStackTraceContaining("2048");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static ConfigurableApplicationContext start(Map<String, String> properties) {
    // the blank key comes first so a variable of the machine cannot supply it; later arguments win
    List<String> args = new ArrayList<>(List.of("--server.port=0", "--JWT_PUBLIC_KEY=", "--MONGODB_URI=mongodb://localhost:27017/identity_audit"));
    properties.forEach((name, value) -> args.add("--" + name + "=" + value));
    return SpringApplication.run(IdentityAuditApplication.class, args.toArray(String[]::new));
  }
}
