package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;

/** An event waiting to be told to the rest of the platform, with the full new state of the tenant. */
public record TenantEvent(
    String eventId, TenantEventType type, Tenant tenant, String actor, String correlationId, Instant occurredAt) {}
