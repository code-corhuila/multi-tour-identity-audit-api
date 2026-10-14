package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.time.Clock;

/**
 * The Tenant Administrator chooses the two colors of their own company. The tenant is the caller's and is never
 * named by the request, so another tenant cannot be asked for; the same colors again leave the same state.
 */
public final class SetTenantVisualIdentity {

  private final TenantRepository tenants;
  private final Clock clock;

  public SetTenantVisualIdentity(TenantRepository tenants, Clock clock) {
    this.tenants = tenants;
    this.clock = clock;
  }

  public Tenant execute(Caller caller, String primaryColor, String secondaryColor) {
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    VisualIdentity identity = new VisualIdentity(primaryColor, secondaryColor);
    String tenantId = caller.tenantId();
    Tenant current = tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    Tenant next = current.withVisualIdentity(identity, clock.instant());
    if (!tenants.saveVisualIdentity(tenantId, identity, next.updatedAt())) {
      throw new BusinessRuleViolationException("The tenant changed while the request was being decided; try again");
    }
    return next;
  }
}