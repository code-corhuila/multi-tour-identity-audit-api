package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The port as the real adapter must keep it: only the due pending events, oldest first, and a write that checks. */
final class InMemoryOutbox implements OutboxRepository {

  final Map<String, OutboxEvent> events = new HashMap<>();
  /** Runs just before the next update: another request that arrives first, with real state. */
  Runnable beforeNextWrite;
  int writes;

  @Override
  public Page<OutboxEvent> due(Instant now, int page, int limit) {
    List<OutboxEvent> all = events.values().stream()
        .filter(e -> e.status() == OutboxEvent.Status.PENDING && !e.nextAttemptAt().isAfter(now))
        .sorted(Comparator.comparing(OutboxEvent::occurredAt).thenComparing(OutboxEvent::eventId))
        .toList();
    int from = (int) Math.min((long) (page - 1) * limit, all.size());
    return new Page<>(all.subList(from, Math.min(from + limit, all.size())), page, limit, all.size());
  }

  @Override
  public Optional<OutboxEvent> findById(String eventId) {
    return Optional.ofNullable(events.get(eventId));
  }

  @Override
  public void update(OutboxEvent expected, OutboxEvent next) {
    if (beforeNextWrite != null) {
      Runnable winner = beforeNextWrite;
      beforeNextWrite = null;
      winner.run();
    }
    if (!expected.equals(events.get(expected.eventId()))) {
      throw new ConcurrentChangeException(expected.eventId());
    }
    writes++;
    events.put(next.eventId(), next);
  }
}
