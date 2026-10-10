package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;

/**
 * An event waiting to be told to the rest of the platform, with the full new state of the tenant so a consumer
 * applies the newest one (ADR-004 D4). The reason of a status change is the one the tenant carries. Its text
 * form leaves out the tenant's free text.
 */
public record TenantEvent(
    String eventId, TenantEventType type, Tenant tenant, String actor, String correlationId, Instant occurredAt) {

  public TenantEvent {
    if (eventId == null || type == null || tenant == null || occurredAt == null) {
      throw new IllegalArgumentException("An event has an id, a type, a tenant and a time");
    }
    requireWho(actor, correlationId);
  }

  /** The actor (100 characters at most, as in a tenant) and the correlation id (128, as the header) of an event. */
  static void requireWho(String actor, String correlationId) {
    if (actor == null || actor.isBlank() || actor.length() > 100 || correlationId == null
        || correlationId.isBlank() || correlationId.length() > 128) {
      throw new IllegalArgumentException("An event has an actor and a correlation id of a sensible length");
    }
  }

  @Override
  public String toString() {
    return "TenantEvent[" + eventId + ", " + type + ", " + tenant.tenantId() + "]";
  }
}
