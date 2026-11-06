package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditAction;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import java.time.Instant;
import java.util.Map;

/**
 * An event waiting to be told to the rest of the platform, with the full new state of the tenant so a consumer
 * applies the newest one (ADR-004 D4). The reason of a status change is the one the tenant carries. Its text
 * form leaves out the tenant's free text.
 */
public record TenantEvent(
    String eventId, TenantEventType type, Tenant tenant, String actor, String correlationId, Instant occurredAt) {

  public TenantEvent {
    if (eventId == null || type == null || tenant == null || occurredAt == null) {
      throw new InvalidDataException("An event has an id, a type, a tenant and a time");
    }
    requireWho(actor, correlationId);
  }

  /**
   * The actor (100 characters at most, as in a tenant, trimmed and with no control character) and the
   * correlation id (128, with no white space, as the header): what the validator of the database accepts.
   */
  static void requireWho(String actor, String correlationId) {
    if (actor == null || actor.isBlank() || actor.length() > 100 || !actor.equals(actor.strip())
        || actor.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidDataException("An event has an actor, trimmed, of at most 100 characters");
    }
    if (correlationId == null || correlationId.isEmpty() || correlationId.length() > 128
        || correlationId.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      throw new InvalidDataException("An event has a correlation id of at most 128 characters without spaces");
    }
  }

  /**
   * The audit record of this change (PDR section 17): who did it, why, and the status of the tenant before and after. It
   * has the id of the event, so the record and the event that tells the platform are one fact seen twice.
   */
  public AuditRecord toAuditRecord(String auditRecordId, Tenant previous) {
    return new AuditRecord(auditRecordId, eventId, tenant.tenantId(), actor, AuditAction.of(type.eventType()),
        "tenants/" + tenant.tenantId(), tenant.statusReason(), Map.of("tenantStatus", previous.status().name()),
        Map.of("tenantStatus", tenant.status().name()), null, null, occurredAt);
  }

  @Override
  public String toString() {
    return "TenantEvent[" + eventId + ", " + type + ", " + tenant.tenantId() + "]";
  }
}
