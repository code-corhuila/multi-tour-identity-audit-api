package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

import java.time.Instant;
import java.util.Map;

/**
 * An event of the outbox as job 7 reads it (ADR-004 D4). The owner is the only one that changes it: it is
 * published when every consumer took it and failed when its attempts ran out.
 */
public record OutboxEvent(
    String eventId, String eventType, int eventVersion, String tenantId, String aggregateId, String actorId,
    String reason, Map<String, Object> payload, String correlationId, Instant occurredAt, Instant publishedAt,
    Status status, int attempts, String lastError, Instant nextAttemptAt) {

  public enum Status {
    PENDING,
    PUBLISHED,
    FAILED
  }

  /** Published, whatever it was: a delivery that did succeed is true even if the event had been given up. */
  public OutboxEvent published(Instant now) {
    if (status == Status.PUBLISHED) {
      return this;
    }
    return new OutboxEvent(
        eventId, eventType, eventVersion, tenantId, aggregateId, actorId, reason, payload, correlationId, occurredAt,
        now, Status.PUBLISHED, attempts, lastError, nextAttemptAt);
  }

  /** One more failed delivery; an event no longer pending is not touched by a report that came late. */
  public OutboxEvent withFailedAttempt(String error, Instant now, RetryPolicy policy) {
    if (status != Status.PENDING) {
      return this;
    }
    int count = attempts + 1;
    boolean lastOne = count >= policy.maxAttempts();
    return new OutboxEvent(
        eventId, eventType, eventVersion, tenantId, aggregateId, actorId, reason, payload, correlationId, occurredAt,
        publishedAt, lastOne ? Status.FAILED : Status.PENDING, count, error,
        lastOne ? now : now.plus(policy.waitAfter(count)));
  }
}
