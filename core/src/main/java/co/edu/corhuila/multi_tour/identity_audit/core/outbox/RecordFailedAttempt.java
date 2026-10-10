package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;

/** A consumer did not take the event: it counts, keeps the error and waits longer; the last attempt gives it up. */
public final class RecordFailedAttempt {

  public record Command(String eventId, String error) {

    public Command {
      EventChange.requireEventId(eventId);
      if (error == null || error.isBlank()) {
        throw new InvalidDataException("The error is required");
      }
      error = error.strip();
      if (error.length() > 1000 || error.chars().anyMatch(Character::isISOControl)) {
        throw new InvalidDataException("The error is at most 1000 characters with no control character");
      }
    }
  }

  private final EventChange change;
  private final Clock clock;
  private final RetryPolicy policy;

  public RecordFailedAttempt(OutboxRepository outbox, Clock clock, RetryPolicy policy) {
    this.change = new EventChange(outbox);
    this.clock = clock;
    this.policy = policy;
  }

  public OutboxEvent execute(Command command) {
    return change.run(command.eventId(), event -> event.withFailedAttempt(command.error(), clock.instant(), policy));
  }
}
