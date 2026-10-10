package co.edu.corhuila.multi_tour.identity_audit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.outbox.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The wait between attempts comes from the configuration, so a typo must fail at startup and say which value. */
class OutboxRetryConfigTest {

  private final CompositionRoot root = new CompositionRoot();

  @Test
  void C_the_values_of_the_annex_build_the_policy() {
    RetryPolicy policy = root.outboxRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);
    assertThat(policy.waitAfter(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.maxAttempts()).isEqualTo(20);
  }

  @Test
  void C_a_base_under_a_second_a_cap_over_a_day_or_too_many_attempts_stop_the_start_naming_the_properties() {
    for (Object[] bad : new Object[][] {
        {Duration.ofMillis(5), Duration.ofMinutes(15), 20}, {Duration.ofSeconds(5), Duration.ofHours(25), 20},
        {Duration.ofSeconds(5), Duration.ofMinutes(15), 1001}}) {
      assertThatThrownBy(() -> root.outboxRetryPolicy((Duration) bad[0], (Duration) bad[1], (int) bad[2]))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("outbox.retry.base");
    }
  }
}
