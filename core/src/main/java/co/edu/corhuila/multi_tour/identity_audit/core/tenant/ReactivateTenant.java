package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** The Platform Administrator lets an inactive tenant operate again, on purpose and with a reason. */
public final class ReactivateTenant {

  public record Command(String tenantId, String actor, String reason, String correlationId) {

    public Command {
      actor = StatusChange.check(tenantId, actor, reason, correlationId);
    }
  }

  private final StatusChange change;

  public ReactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    this.change = new StatusChange(tenants, ids, clock, TenantEventType.TENANT_REACTIVATED);
  }

  public Tenant execute(Command command) {
    return change.run(
        command.tenantId(), command.actor(), command.correlationId(),
        (tenant, now) -> tenant.reactivate(command.actor(), command.reason(), now));
  }
}
