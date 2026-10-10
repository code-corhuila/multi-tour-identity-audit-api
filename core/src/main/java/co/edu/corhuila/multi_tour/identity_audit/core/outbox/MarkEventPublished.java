package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import java.time.Clock;

/** Job 7 says every consumer took the event; a repeat answers the event as it is. */
public final class MarkEventPublished {

  private final EventChange change;
  private final Clock clock;

  public MarkEventPublished(OutboxRepository outbox, Clock clock) {
    this.change = new EventChange(outbox);
    this.clock = clock;
  }

  public OutboxEvent execute(String eventId) {
    EventChange.requireEventId(eventId);
    return change.run(eventId, event -> event.published(clock.instant()));
  }
}
