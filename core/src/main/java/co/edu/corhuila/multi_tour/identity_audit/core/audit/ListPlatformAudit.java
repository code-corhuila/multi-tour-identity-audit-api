package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;

/** The Platform Administrator reads the audit across tenants, narrowed to one tenant if they ask (HU-IDA-002 AC3 and AC8). */
public final class ListPlatformAudit {

  private final AuditReader reader;

  public ListPlatformAudit(AuditReader reader) {
    this.reader = reader;
  }

  public Page<AuditRecord> execute(Caller caller, AuditQuery query) {
    if (!caller.roles().contains(Role.PLATFORM_ADMINISTRATOR)) {
      throw new CallerNotAllowedException();
    }
    return reader.page(query);
  }
}
