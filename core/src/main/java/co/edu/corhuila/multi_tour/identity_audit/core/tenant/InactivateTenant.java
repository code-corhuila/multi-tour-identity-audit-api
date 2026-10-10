package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** The Platform Administrator stops a tenant, with a reason. */
public final class InactivateTenant {

  public record Command(String tenantId, String actor, String reason, String correlationId) {}

  public InactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {}

  public Tenant execute(Command command) {
    throw new UnsupportedOperationException();
  }
}
