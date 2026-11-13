package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;

/** Where the audit records are read from, newest first and by id among equal times, so a page never repeats or skips a row. */
public interface AuditReader {

  Page<AuditRecord> page(AuditQuery query);
}
