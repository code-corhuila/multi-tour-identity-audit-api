package co.edu.corhuila.multi_tour.identity_audit.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionCutoffTest {

  private static final Instant T10 = Instant.parse("2026-10-24T10:00:00Z");
  private static final Instant T20 = Instant.parse("2026-10-24T20:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-24T21:00:00Z");
  private static final SessionCutoff AT_20 = new SessionCutoff("account-1", "tenant-1", T20, T20);

  @Test
  void D_a_cutoff_never_moves_back() {
    SessionCutoff older = AT_20.moveTo(T10, NOW);

    assertThat(older).as("an older event changes nothing, not even the update time").isEqualTo(AT_20);
  }

  @Test
  void D_an_equal_cutoff_changes_nothing() {
    assertThat(AT_20.moveTo(T20, NOW)).isEqualTo(AT_20);
  }

  @Test
  void D_a_newer_cutoff_moves_forward_and_keeps_the_account_and_the_tenant() {
    SessionCutoff newer = AT_20.moveTo(NOW, NOW);

    assertThat(newer).isEqualTo(new SessionCutoff("account-1", "tenant-1", NOW, NOW));
  }
}
