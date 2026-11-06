package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An event of the platform as job 7 delivers it (ADR-004 D4), and the audit record it makes. Today every event that reaches
 * this service comes from the security microservice, which is the actor of an event nobody caused (a sign-in that blocks an
 * account); a publisher that appears later brings its own name here.
 */
public record PlatformEvent(
    String eventId, String eventType, String tenantId, String aggregateId, String actorId, String reason,
    Map<String, Object> payload, Instant occurredAt) {

  static final String SECURITY_SERVICE = "multi-tour-security";

  /** The events whose aggregate is the tenant; every other event of the security service is about an account. */
  private static final Set<String> TENANT_EVENTS = Set.of("OptionalRoleEnabled", "OptionalRoleDisabled");

  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  public AuditRecord toAuditRecord(String auditRecordId) {
    if (aggregateId == null || !UUID_FORMAT.matcher(aggregateId).matches()) {
      throw new InvalidDataException("aggregateId", "must be a lowercase UUID");
    }
    String prefix = TENANT_EVENTS.contains(eventType) ? "tenants/" : "accounts/";
    Map<String, Object> body = payload == null ? Map.of() : payload;
    return new AuditRecord(auditRecordId, eventId, tenantId, actorId == null ? SECURITY_SERVICE : actorId,
        AuditAction.of(eventType), prefix + aggregateId, reason, objectOf(body.get("previousValue")), payload,
        textOf(body.get("channelOrModule")), textOf(body.get("functionalProcessReference")), occurredAt);
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
