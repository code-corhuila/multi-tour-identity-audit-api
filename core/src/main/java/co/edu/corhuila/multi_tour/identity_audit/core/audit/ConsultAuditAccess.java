package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.util.List;

/** The Tenant Administrator sees which optional roles may consult the audit of their tenant. */
public final class ConsultAuditAccess {

  private final AuditAccessSettings settings;

  public ConsultAuditAccess(AuditAccessSettings settings) {
    this.settings = settings;
  }

  public List<AuditAccessSetting> execute(Caller caller) {
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    return settings.find(caller.tenantId());
  }
}
