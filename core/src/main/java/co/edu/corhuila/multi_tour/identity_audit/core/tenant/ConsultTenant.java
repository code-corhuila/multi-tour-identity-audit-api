package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The Platform Administrator consults one tenant, whatever its status. */
public final class ConsultTenant {

  public ConsultTenant(TenantRepository tenants) {}

  public Tenant execute(String tenantId) {
    throw new UnsupportedOperationException();
  }
}
