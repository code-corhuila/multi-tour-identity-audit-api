package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxUseCasesTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private static final String UNKNOWN = "00000000-0000-4000-8000-0000000000ff";

  private final InMemoryOutbox outbox = new InMemoryOutbox();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);
  private final MarkEventPublished publish = new MarkEventPublished(outbox, clock);
  private final RecordFailedAttempt attempt = new RecordFailedAttempt(outbox, clock, policy);

  private static OutboxEvent event(String id, long minutesAgo, Instant next) {
    return new OutboxEvent(
        id, "TenantCreated", 1, "00000000-0000-4000-8000-0000000000aa", "00000000-0000-4000-8000-0000000000aa",
        "onboard-tenant", null, Map.of("tenantStatus", "ACTIVE"), "corr-1", NOW.minus(Duration.ofMinutes(minutesAgo)), null,
        OutboxEvent.Status.PENDING, 0, null, next);
  }

  private OutboxEvent stored(OutboxEvent event) {
    outbox.events.put(event.eventId(), event);
    return event;
  }

  @Test
  void A_marking_published_keeps_the_time_once_and_a_repeat_changes_nothing() {
    stored(event(ID, 1, NOW));
    var done = publish.execute(ID);
    assertThat(done.status()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    assertThat(done.publishedAt()).isEqualTo(NOW);
    var later = new MarkEventPublished(outbox, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    assertThat(later.execute(ID)).isEqualTo(done);
    assertThat(outbox.writes).isEqualTo(1);
  }

  @Test
  void A_an_event_that_was_marked_failed_is_published_if_a_delivery_did_succeed() {
    var failed = stored(event(ID, 1, NOW).withFailedAttempt("x", NOW, new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 1)));
    assertThat(failed.status()).isEqualTo(OutboxEvent.Status.FAILED);
    var done = publish.execute(ID);
    assertThat(done.status()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    assertThat(done.attempts()).isEqualTo(1);
    assertThat(done.lastError()).isEqualTo("x");
  }

  @Test
  void A_an_event_that_does_not_exist_or_an_id_that_is_not_a_uuid_is_refused() {
    assertThatThrownBy(() -> publish.execute(UNKNOWN)).isInstanceOf(OutboxEventNotFoundException.class);
    assertThatThrownBy(() -> attempt.execute(new RecordFailedAttempt.Command(UNKNOWN, "down")))
        .isInstanceOf(OutboxEventNotFoundException.class);
    assertThatThrownBy(() -> publish.execute("nope")).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RecordFailedAttempt.Command("nope", "down")).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void A_a_failed_attempt_counts_keeps_the_error_and_waits_double_up_to_the_cap() {
    stored(event(ID, 1, NOW));
    var first = attempt.execute(new RecordFailedAttempt.Command(ID, "  consumer answered 503 "));
    assertThat(first.attempts()).isEqualTo(1);
    assertThat(first.lastError()).isEqualTo("consumer answered 503");
    assertThat(first.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(attempt.execute(new RecordFailedAttempt.Command(ID, "again")).nextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(policy.waitAfter(2)).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.waitAfter(8)).isEqualTo(Duration.ofSeconds(640));
    assertThat(policy.waitAfter(9)).isEqualTo(Duration.ofMinutes(15));
    assertThat(policy.waitAfter(19)).isEqualTo(Duration.ofMinutes(15));
    assertThat(policy.waitAfter(500)).isEqualTo(Duration.ofMinutes(15));
    assertThat(policy.waitAfter(0)).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void A_at_the_last_attempt_the_event_is_failed_and_stays_out_of_the_listing() {
    stored(event(ID, 1, NOW));
    OutboxEvent last = null;
    for (int i = 0; i < 20; i++) {
      last = attempt.execute(new RecordFailedAttempt.Command(ID, "down"));
    }
    assertThat(last.status()).isEqualTo(OutboxEvent.Status.FAILED);
    assertThat(last.attempts()).isEqualTo(20);
    assertThat(outbox.due(NOW.plusSeconds(3600), 1, 20).data()).isEmpty();
    assertThat(attempt.execute(new RecordFailedAttempt.Command(ID, "late"))).isEqualTo(last);
    assertThat(outbox.writes).isEqualTo(20);
  }

  @Test
  void A_an_attempt_on_a_published_event_is_a_stale_report_and_changes_nothing() {
    stored(event(ID, 1, NOW));
    var done = publish.execute(ID);
    assertThat(attempt.execute(new RecordFailedAttempt.Command(ID, "late"))).isEqualTo(done);
    assertThat(outbox.writes).isEqualTo(1);
  }

  @Test
  void A_the_error_is_required_trimmed_and_of_1000_characters_at_most() {
    for (String bad : new String[] {null, "", "   ", "e".repeat(1001), "a\nb"}) {
      assertThatThrownBy(() -> new RecordFailedAttempt.Command(ID, bad)).isInstanceOf(InvalidDataException.class);
    }
    assertThat(new RecordFailedAttempt.Command(ID, "e".repeat(1000)).error()).hasSize(1000);
  }

  @Test
  void A_an_error_with_a_bidi_override_a_zero_width_or_a_line_separator_is_refused_and_a_plain_space_is_not() {
    for (String bad : new String[] {"ok\u202eevil", "ok\u200bevil", "ok\u2028evil", "ok\u2029evil", "ok\u00a0evil", "ok\u3000evil", "ok\ufeffevil", "ok\uDB40\uDC41evil", "ok\uD800evil", "ok\ue000evil"}) {
      assertThatThrownBy(() -> new RecordFailedAttempt.Command(ID, bad)).as(bad).isInstanceOf(InvalidDataException.class);
    }
    assertThat(new RecordFailedAttempt.Command(ID, "consumer answered 503 twice").error()).isEqualTo("consumer answered 503 twice");
    String withEmoji = "consumer said \uD83D\uDE00 and caf\u00e9";
    assertThat(new RecordFailedAttempt.Command(ID, withEmoji).error()).isEqualTo(withEmoji);
  }

  @Test
  void A_a_request_that_raced_with_another_decides_again_on_what_the_other_left() {
    stored(event(ID, 1, NOW));
    outbox.beforeNextWrite = () -> publish.execute(ID);
    var result = attempt.execute(new RecordFailedAttempt.Command(ID, "down"));
    assertThat(result.status()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    assertThat(result.attempts()).isZero();
    assertThat(List.copyOf(outbox.events.values())).hasSize(1);
  }

  @Test
  void A_the_policy_refuses_values_that_make_no_sense() {
    assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofMinutes(15), 20)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RetryPolicy(Duration.ofMinutes(20), Duration.ofMinutes(15), 20)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 0)).isInstanceOf(InvalidDataException.class);
  }
}
