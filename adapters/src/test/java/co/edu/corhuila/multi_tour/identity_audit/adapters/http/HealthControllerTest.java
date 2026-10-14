package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** /health says the service is up and, apart, whether the database answers; a database that does not is not a 503. */
class HealthControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void A_health_shows_the_database_up() {
    var health = new HealthController(() -> true, clock).health();
    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.timestamp()).isEqualTo(NOW);
    assertThat(health.dependencies().database()).isEqualTo("ok");
  }

  @Test
  void A_health_shows_the_database_down_and_the_service_still_up() {
    var health = new HealthController(() -> false, clock).health();
    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.dependencies().database()).isEqualTo("down");
  }

  @Test
  void A_a_database_check_that_fails_is_down_and_not_an_error() {
    var health = new HealthController(() -> { throw new IllegalStateException("mongodb://user:secret@host"); }, clock).health();
    assertThat(health.dependencies().database()).isEqualTo("down");
    assertThat(health.toString()).doesNotContain("secret");
  }
}
