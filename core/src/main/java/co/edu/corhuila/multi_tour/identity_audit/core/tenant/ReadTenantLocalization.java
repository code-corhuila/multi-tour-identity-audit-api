package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;

/**
 * The Tenant Administrator reads the zone and the country of their own tenant, the one in the token, also when it is inactive: the screen
 * of HU-IDA-004 shows what is set before a change (spec 010, rule RN-IDA-API-107).
 */
public final class ReadTenantLocalization {

  private final TenantRepository tenants;

  public ReadTenantLocalization(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Tenant execute(Caller caller) {
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    return tenants.findById(caller.tenantId()).orElseThrow(() -> new TenantNotFoundException(caller.tenantId()));
  }
}
