package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import java.time.Clock;

/** Job 7 reads the events waiting for delivery, oldest first. */
public final class ListDueEvents {

  /** {@code page} from 1 and {@code limit} from 1 to 100, never trimmed. */
  public record Query(int page, int limit) {

    public Query {
      if (page < 1 || limit < 1 || limit > 100) {
        throw new InvalidDataException("The page starts at 1 and the limit is from 1 to 100");
      }
    }
  }

  private final OutboxRepository outbox;
  private final Clock clock;

  public ListDueEvents(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  public Page<OutboxEvent> execute(Query query) {
    return outbox.due(clock.instant(), query.page(), query.limit());
  }
}
