package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.PlatformEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.RecordPlatformEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An event as job 7 delivers it (ADR-004 D4), shared by the route of a tenant and the route of the events that belong to
 * none. Texts, not anything else; it tolerates a field it does not name, because a publisher may add some. Each route judges
 * the tenant on its own before it calls {@link #receive}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PlatformEventEnvelope(String eventId, String eventType, String tenantId, String aggregateId, String actorId, String reason,
    Map<String, Object> payload, String occurredAt) {

  /** Every event becomes an audit record, and a session revocation also moves the cutoff of its account. */
  ResponseEntity<Void> receive(String idempotencyKey, ApplySessionRevocation apply, RecordPlatformEvent record) {
    if (idempotencyKey == null || !idempotencyKey.equals(eventId)) {
      throw new InvalidDataException("Idempotency-Key", "must be the id of the event");
    }
    if (payload == null) {
      throw new InvalidDataException("payload", "is required");
    }
    PlatformEvent event = new PlatformEvent(eventId, eventType, tenantId, aggregateId, actorId, reason, payload, occurredAtInstant());
    if (ApplySessionRevocation.EVENT_TYPE.equals(eventType)) {
      ApplySessionRevocation.Outcome outcome = apply.execute(new ApplySessionRevocation.Command(eventId, eventType, tenantId,
          aggregateId, text(payload.get("accountId")), text(payload.get("revokedFrom"))), event);
      return ResponseEntity.status(outcome == ApplySessionRevocation.Outcome.APPLIED ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
    RecordPlatformEvent.Outcome outcome = record.execute(event);
    return ResponseEntity.status(outcome == RecordPlatformEvent.Outcome.RECORDED ? HttpStatus.CREATED : HttpStatus.OK).build();
  }

  private static String text(Object value) {
    return value instanceof String text ? text : null;
  }

  private Instant occurredAtInstant() {
    try {
      return occurredAt == null ? null : Instant.parse(occurredAt);
    } catch (DateTimeParseException notADate) {
      throw new InvalidDataException("occurredAt", "must be an RFC 3339 date and time");
    }
  }
}
