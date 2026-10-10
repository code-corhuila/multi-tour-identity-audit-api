package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** What publishing and recording an attempt share: the id is checked first, and a race decides again once. */
final class EventChange {

  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final OutboxRepository outbox;

  EventChange(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  static void requireEventId(String eventId) {
    if (eventId == null || !UUID_FORMAT.matcher(eventId).matches()) {
      throw new InvalidDataException("The event id is a lowercase UUID");
    }
  }

  OutboxEvent run(String eventId, UnaryOperator<OutboxEvent> change) {
    try {
      return attempt(eventId, change);
    } catch (ConcurrentChangeException raced) {
      return attempt(eventId, change);
    }
  }

  private OutboxEvent attempt(String eventId, UnaryOperator<OutboxEvent> change) {
    OutboxEvent current = outbox.findById(eventId).orElseThrow(OutboxEventNotFoundException::new);
    OutboxEvent next = change.apply(current);
    if (!next.equals(current)) {
      outbox.update(current, next);
    }
    return next;
  }
}
