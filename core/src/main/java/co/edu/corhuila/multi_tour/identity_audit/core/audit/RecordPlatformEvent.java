package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdGenerator;

/** Every event job 7 delivers makes one audit record (HU-IDA-002 AC1, AC2 and AC7). */
public final class RecordPlatformEvent {

  /** {@code RECORDED} the first time; {@code REPEATED} when a record of the event already existed. */
  public enum Outcome { RECORDED, REPEATED }

  private final AuditRecords records;
  private final IdGenerator ids;

  public RecordPlatformEvent(AuditRecords records, IdGenerator ids) {
    this.records = records;
    this.ids = ids;
  }

  public Outcome execute(PlatformEvent event) {
    return records.append(event.toAuditRecord(ids.newId())) ? Outcome.RECORDED : Outcome.REPEATED;
  }
}
