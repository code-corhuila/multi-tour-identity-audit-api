package co.edu.corhuila.multi_tour.identity_audit.core.audit;

/** Where the audit records are kept. Nothing here changes or deletes one: a correction is a new record. */
public interface AuditRecords {

  /**
   * Keeps the record. It answers {@code false}, keeping nothing, when a record of the same event is already there, so two
   * deliveries of one event at once leave a single record.
   */
  boolean append(AuditRecord record);
}
