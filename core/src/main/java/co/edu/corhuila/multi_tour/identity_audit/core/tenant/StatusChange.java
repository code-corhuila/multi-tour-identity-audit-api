package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;

/**
 * What inactivating and reactivating share: the data is checked before the tenant is looked for, the change
 * and its event are written together, and a request that raced with another decides again once on what the
 * other left (so of two inactivations the second is refused and the first reason stays).
 */
final class StatusChange {

  private final TenantRepository tenants;
  private final IdGenerator ids;
  private final Clock clock;
  private final TenantEventType type;

  StatusChange(TenantRepository tenants, IdGenerator ids, Clock clock, TenantEventType type) {
    this.tenants = tenants;
    this.ids = ids;
    this.clock = clock;
    this.type = type;
  }

  /** Checks the data of a command and answers the actor as the tenant will keep it: trimmed. */
  static String check(String tenantId, String actor, String reason, String correlationId) {
    Tenant.requireUuid(tenantId, "The tenant");
    Tenant.text(reason, 500, "The reason");
    TenantEvent.requireWho(actor, correlationId);
    return Tenant.text(actor, 100, "The actor");
  }

  Tenant run(String tenantId, String actor, String correlationId, BiFunction<Tenant, Instant, Tenant> change) {
    try {
      return attempt(tenantId, actor, correlationId, change);
    } catch (ConcurrentChangeException raced) {
      return attempt(tenantId, actor, correlationId, change);
    }
  }

  private Tenant attempt(String tenantId, String actor, String correlationId, BiFunction<Tenant, Instant, Tenant> change) {
    Tenant current = tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    Tenant next = change.apply(current, clock.instant());
    TenantEvent event = new TenantEvent(ids.newId(), type, next, actor, correlationId, next.updatedAt());
    tenants.update(current, next, List.of(event));
    return next;
  }
}
