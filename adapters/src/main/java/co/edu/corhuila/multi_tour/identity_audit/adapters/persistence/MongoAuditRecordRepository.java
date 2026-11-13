package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecords;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.util.Date;
import org.bson.Document;
import org.bson.codecs.configuration.CodecConfigurationException;

/**
 * The audit records in {@code audit_records}. The unique index on the event id is what makes two deliveries of one event at
 * once leave a single record, so nothing is read before the write. The service only ever inserts here.
 */
public final class MongoAuditRecordRepository implements AuditRecords {

  private final MongoCollection<Document> records;

  public MongoAuditRecordRepository(MongoClient client, String database) {
    // a committed write must survive a failover, whatever the URI of the connection says
    this.records = client.getDatabase(database).getCollection("audit_records").withWriteConcern(WriteConcern.MAJORITY);
  }

  @Override
  public boolean append(AuditRecord record) {
    try {
      records.insertOne(toDocument(record));
      return true;
    } catch (MongoWriteException e) {
      if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
        return false;
      }
      throw e;
    } catch (CodecConfigurationException unsupported) {
      // a number past a long, or any other value the driver has no encoding for: the caller's payload, not our failure
      throw new InvalidDataException("payload", "holds a value that cannot be kept");
    }
  }

  static Document toDocument(AuditRecord record) {
    Document document = new Document("_id", record.auditRecordId()).append("eventId", record.eventId());
    putIfPresent(document, "tenantId", record.tenantId());
    document.append("actorId", record.actorId()).append("action", record.action()).append("affectedRecordId", record.affectedRecordId());
    putIfPresent(document, "reason", record.reason());
    putIfPresent(document, "previousValue", record.previousValue());
    putIfPresent(document, "newValue", record.newValue());
    putIfPresent(document, "channelOrModule", record.channelOrModule());
    putIfPresent(document, "functionalProcessReference", record.functionalProcessReference());
    return document.append("recordedAt", Date.from(record.recordedAt()));
  }

  private static void putIfPresent(Document document, String field, Object value) {
    if (value != null) {
      document.append(field, value);
    }
  }
}
