package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The Platform Administrator consults one tenant, whatever its status. */
public final class ConsultTenant {

  private final TenantRepository tenants;

  public ConsultTenant(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Tenant execute(String tenantId) {
    Tenant.requireUuid(tenantId, "The tenant");
    return tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
  }
}
