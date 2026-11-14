package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;

/** The Tenant Administrator takes away the logo or a commercial image of their own company; there being none is not an error. */
public final class RemoveTenantImage {

  private final TenantRepository tenants;
  private final TenantImages images;

  public RemoveTenantImage(TenantRepository tenants, TenantImages images) {
    this.tenants = tenants;
    this.images = images;
  }

  public void execute(Caller caller, ImageSlot slot, int position) {
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    slot.requirePosition(position);
    String tenantId = caller.tenantId();
    Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    if (tenant.status() != TenantStatus.ACTIVE) {
      throw new BusinessRuleViolationException("A " + tenant.status() + " tenant cannot change its logo or its images");
    }
    images.remove(tenantId, slot, position);
  }
}
