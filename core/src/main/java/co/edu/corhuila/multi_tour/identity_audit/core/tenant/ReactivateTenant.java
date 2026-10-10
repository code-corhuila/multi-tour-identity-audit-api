package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** The Platform Administrator lets an inactive tenant operate again, on purpose and with a reason. */
public final class ReactivateTenant {

  public record Command(String tenantId, String actor, String reason, String correlationId) {}

  public ReactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {}

  public Tenant execute(Command command) {
    throw new UnsupportedOperationException();
  }
}
