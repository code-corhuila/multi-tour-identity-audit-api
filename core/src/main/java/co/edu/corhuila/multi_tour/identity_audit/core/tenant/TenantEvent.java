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

  /**
   * The actor (100 characters at most, as in a tenant, trimmed and with no control character) and the
   * correlation id (128, with no white space, as the header): what the validator of the database accepts.
   */
  static void requireWho(String actor, String correlationId) {
    if (actor == null || actor.isBlank() || actor.length() > 100 || !actor.equals(actor.strip())
        || actor.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("An event has an actor, trimmed, of at most 100 characters");
    }
    if (correlationId == null || correlationId.isEmpty() || correlationId.length() > 128
        || correlationId.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      throw new IllegalArgumentException("An event has a correlation id of at most 128 characters without spaces");
    }
  }

  @Override
  public String toString() {
    return "TenantEvent[" + eventId + ", " + type + ", " + tenant.tenantId() + "]";
  }
}
