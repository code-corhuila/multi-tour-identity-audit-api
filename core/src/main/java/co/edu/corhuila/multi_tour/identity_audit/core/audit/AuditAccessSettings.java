package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import java.util.List;

/** Where the audit access of the optional roles is kept, one setting per tenant and role. */
public interface AuditAccessSettings {

  /** The three optional roles of the tenant, in the order of {@link AuditAccessSetting#OPTIONAL_ROLES}; one never set comes back off. */
  List<AuditAccessSetting> find(String tenantId);

  /**
   * Replaces {@code expected} with {@code next} and keeps {@code audit} in the same transaction, so the setting and its
   * evidence are kept together or not at all. It throws a {@code ConcurrentChangeException}, writing nothing, when the
   * setting is no longer the one the decision was made on.
   */
  void change(String tenantId, AuditAccessSetting expected, AuditAccessSetting next, AuditRecord audit);
}
