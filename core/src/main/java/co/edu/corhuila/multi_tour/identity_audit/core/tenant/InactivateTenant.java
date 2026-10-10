package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** The Platform Administrator stops a tenant, with a reason. Only an active tenant can be stopped. */
public final class InactivateTenant {

  public record Command(String tenantId, String actor, String reason, String correlationId) {

    public Command {
      actor = StatusChange.check(tenantId, actor, reason, correlationId);
    }
  }

  private final StatusChange change;

  public InactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    this.change = new StatusChange(tenants, ids, clock, TenantEventType.TENANT_INACTIVATED);
  }

  public Tenant execute(Command command) {
    return change.run(
        command.tenantId(), command.actor(), command.correlationId(),
        (tenant, now) -> tenant.inactivate(command.actor(), command.reason(), now));
  }
}
