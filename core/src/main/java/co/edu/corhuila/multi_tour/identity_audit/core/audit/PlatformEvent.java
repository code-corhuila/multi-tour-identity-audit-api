package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An event of the platform as job 7 delivers it (ADR-004 D4), and the audit record it makes. The actor is the person the envelope
 * names, else the one of the payload, else the service that writes that type of event (a sign-in that blocks an account is the
 * security service's own); what the record is about follows the type ({@link EventOrigin}).
 */
public record PlatformEvent(
    String eventId, String eventType, String tenantId, String aggregateId, String actorId, String reason,
    Map<String, Object> payload, Instant occurredAt) {

  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  public AuditRecord toAuditRecord(String auditRecordId) {
    if (aggregateId == null || !UUID_FORMAT.matcher(aggregateId).matches()) {
      throw new InvalidDataException("aggregateId", "must be a lowercase UUID");
    }
    EventOrigin.Origin origin = EventOrigin.of(eventType);
    Map<String, Object> body = payload == null ? Map.of() : payload;
    return new AuditRecord(auditRecordId, eventId, tenantId, actor(origin, body),
        AuditAction.of(eventType), origin.recordPrefix() + aggregateId, reason, objectOf(body.get("previousValue")), payload,
        textOf(body.get("channelOrModule")), textOf(body.get("functionalProcessReference")), occurredAt);
  }

  /** The person of the envelope, else the one of the payload, else the service that wrote the event. */
  private String actor(EventOrigin.Origin origin, Map<String, Object> body) {
    if (actorId != null && !actorId.isBlank()) {
      return actorId;
    }
    return body.get("actorId") instanceof String person && !person.isBlank() ? person : origin.service();
  }

  private static String textOf(Object value) {
    return value instanceof String text ? text : null;
  }

  private static Map<String, Object> objectOf(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
    return copy;
  }
}
