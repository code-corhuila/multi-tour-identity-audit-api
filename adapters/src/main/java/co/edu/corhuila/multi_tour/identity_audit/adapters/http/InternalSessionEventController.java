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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What job 7 delivers: every event becomes an audit record, and a session revocation also moves the cutoff of its account. The role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants/{tenantId}/events")
class InternalSessionEventController {

  private final ApplySessionRevocation apply;
  private final RecordPlatformEvent record;

  InternalSessionEventController(ApplySessionRevocation apply, RecordPlatformEvent record) {
    this.apply = apply;
    this.record = record;
  }

  /** Texts, not anything else. The other routes refuse a field they do not name; this one tolerates it, because a publisher may add some (ADR-004 D4). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Envelope(String eventId, String eventType, String tenantId, String aggregateId, String actorId, String reason,
      Map<String, Object> payload, String occurredAt) {}

  @PostMapping
  ResponseEntity<Void> receive(
      @PathVariable String tenantId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Envelope body) {
    if (key == null || !key.equals(body.eventId())) {
      throw new InvalidDataException("Idempotency-Key", "must be the id of the event");
    }
    if (body.payload() == null) {
      throw new InvalidDataException("payload", "is required");
    }
    if (!tenantId.equals(body.tenantId())) {
      throw new InvalidDataException("tenantId", "must be the tenant of the path");
    }
    PlatformEvent event = new PlatformEvent(body.eventId(), body.eventType(), body.tenantId(), body.aggregateId(),
        body.actorId(), body.reason(), body.payload(), occurredAt(body.occurredAt()));
    if (ApplySessionRevocation.EVENT_TYPE.equals(body.eventType())) {
      ApplySessionRevocation.Outcome outcome = apply.execute(new ApplySessionRevocation.Command(body.eventId(), body.eventType(),
          body.tenantId(), body.aggregateId(), text(body.payload().get("accountId")), text(body.payload().get("revokedFrom"))), event);
      return ResponseEntity.status(outcome == ApplySessionRevocation.Outcome.APPLIED ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
    RecordPlatformEvent.Outcome outcome = record.execute(event);
    return ResponseEntity.status(outcome == RecordPlatformEvent.Outcome.RECORDED ? HttpStatus.CREATED : HttpStatus.OK).build();
  }

  private static String text(Object value) {
    return value instanceof String text ? text : null;
  }

  private static Instant occurredAt(String value) {
    try {
      return value == null ? null : Instant.parse(value);
    } catch (DateTimeParseException notADate) {
      throw new InvalidDataException("occurredAt", "must be an RFC 3339 date and time");
    }
  }
}
