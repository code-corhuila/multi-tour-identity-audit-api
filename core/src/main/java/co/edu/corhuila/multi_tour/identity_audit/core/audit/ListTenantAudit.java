package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantNotFoundException;

/**
 * A user of a tenant reads the audit of their own tenant (HU-IDA-002 AC4, AC5 and AC8): the Tenant Administrator always, a
 * Manager, an Accountant or an Analyst only while their role has the access in that tenant, nobody else. The order of the
 * checks is part of the rule: a caller without a tenant (the Platform Administrator uses its own route) is refused, then
 * another tenant is {@code 404} whatever the role, so nothing tells whether it exists, and only then is the role judged.
 */
public final class ListTenantAudit {

  private final AuditReader reader;
  private final AuditAccessSettings settings;

  public ListTenantAudit(AuditReader reader, AuditAccessSettings settings) {
    this.reader = reader;
    this.settings = settings;
  }

  public Page<AuditRecord> execute(Caller caller, String tenantId, AuditQuery query) {
    if (caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    if (!caller.tenantId().equals(tenantId)) {
      throw new TenantNotFoundException(tenantId);
    }
    if (!mayRead(caller)) {
      throw new CallerNotAllowedException();
    }
    // the tenant is the caller's, whatever the query named
    return reader.page(query.forTenant(tenantId));
  }

  /** By name, so a setting for a role that this code does not know grants nothing instead of failing. */
  private boolean mayRead(Caller caller) {
    if (caller.roles().contains(Role.TENANT_ADMINISTRATOR)) {
      return true;
    }
    return settings.find(caller.tenantId()).stream()
        .anyMatch(setting -> setting.enabled() && caller.roles().stream().anyMatch(role -> role.name().equals(setting.role())));
  }
}
