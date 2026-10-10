package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListDueEventsTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

  private final InMemoryOutbox outbox = new InMemoryOutbox();
  private final ListDueEvents list = new ListDueEvents(outbox, Clock.fixed(NOW, ZoneOffset.UTC));

  private OutboxEvent stored(int n, long minutesAgo, Instant next) {
    String id = "00000000-0000-4000-8000-%012d".formatted(n);
    var event = new OutboxEvent(
        id, "TenantCreated", 1, id, id, "onboard-tenant", null, Map.of(), "corr-1",
        NOW.minus(Duration.ofMinutes(minutesAgo)), null, OutboxEvent.Status.PENDING, 0, null, next);
    outbox.events.put(id, event);
    return event;
  }

  @Test
  void A_the_listing_has_only_the_pending_events_that_are_due_oldest_first_by_page() {
    stored(3, 1, NOW);
    stored(2, 5, NOW.minusSeconds(1));
    var oldest = stored(1, 9, NOW);
    stored(4, 7, NOW.plusSeconds(1));
    var done = stored(5, 8, NOW).published(NOW);
    outbox.events.put(done.eventId(), done);
    var first = list.execute(new ListDueEvents.Query(1, 2));
    assertThat(first.data()).extracting(OutboxEvent::eventId).containsExactly(oldest.eventId(), "00000000-0000-4000-8000-000000000002");
    assertThat(first.total()).isEqualTo(3);
    assertThat(list.execute(new ListDueEvents.Query(2, 2)).data()).hasSize(1);
    assertThat(list.execute(new ListDueEvents.Query(9, 2)).data()).isEmpty();
  }

  @Test
  void A_a_page_below_1_and_a_limit_out_of_1_to_100_are_refused_not_trimmed() {
    for (int[] bad : new int[][] {{0, 20}, {1, 0}, {1, 101}, {-1, 5}}) {
      assertThatThrownBy(() -> new ListDueEvents.Query(bad[0], bad[1])).isInstanceOf(InvalidDataException.class);
    }
    assertThat(new ListDueEvents.Query(1, 100).limit()).isEqualTo(100);
  }
}
