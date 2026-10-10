package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import java.time.Instant;
import java.util.Optional;

/** Where the events wait. Only the owning API changes them (ADR-004 D4). */
public interface OutboxRepository {

  /**
   * The pending events whose next attempt is not after {@code now}, oldest first and then by id. A page past
   * the last is empty with the real total; compute the offset as a long.
   */
  Page<OutboxEvent> due(Instant now, int page, int limit);

  Optional<OutboxEvent> findById(String eventId);

  /**
   * Stores {@code next} only if the stored event is still {@code expected}, the one the change was decided on.
   *
   * @throws co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException if it changed first
   */
  void update(OutboxEvent expected, OutboxEvent next);
}
