package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditQuery;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditReader;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecords;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.conversions.Bson;

/**
 * The audit records in {@code audit_records}. The unique index on the event id is what makes two deliveries of one event at
 * once leave a single record, so nothing is read before the write. The service only ever inserts here and reads, newest
 * first and by id among equal times, which the indexes of {@code identity-audit-db} serve without a sort in memory.
 */
public final class MongoAuditRecordRepository implements AuditRecords, AuditReader {

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

  @Override
  public Page<AuditRecord> page(AuditQuery query) {
    List<Bson> conditions = new ArrayList<>();
    if (query.tenantId() != null) {
      conditions.add(eq("tenantId", query.tenantId()));
    }
    if (query.action() != null) {
      conditions.add(eq("action", query.action()));
    }
    if (query.from() != null) {
      conditions.add(gte("recordedAt", Date.from(query.from())));
    }
    if (query.to() != null) {
      conditions.add(lt("recordedAt", Date.from(query.to())));
    }
    Bson filter = conditions.isEmpty() ? new Document() : and(conditions);
    long total = records.countDocuments(filter, ReadLimit.count());
    long offset = (long) (query.page() - 1) * query.limit();
    // past the end there is nothing to skip to: an empty page, and no walk of the index up to the time limit
    List<AuditRecord> data = offset >= total || offset > Integer.MAX_VALUE
        ? List.of()
        : ReadLimit.of(records.find(filter)).sort(Sorts.orderBy(Sorts.descending("recordedAt"), Sorts.descending("_id")))
            .skip((int) offset).limit(query.limit()).map(MongoAuditRecordRepository::toRecord).into(new ArrayList<>());
    return new Page<>(data, query.page(), query.limit(), total);
  }

  /** A stored record satisfies the validator of the database, so the core accepts it. */
  private static AuditRecord toRecord(Document document) {
    return new AuditRecord(document.getString("_id"), document.getString("eventId"), document.getString("tenantId"),
        document.getString("actorId"), document.getString("action"), document.getString("affectedRecordId"),
        document.getString("reason"), values(document.get("previousValue", Document.class)),
        values(document.get("newValue", Document.class)), document.getString("channelOrModule"),
        document.getString("functionalProcessReference"), document.getDate("recordedAt").toInstant());
  }

  private static Map<String, Object> values(Document document) {
    if (document == null) {
      return null;
    }
    Map<String, Object> plain = new LinkedHashMap<>();
    document.forEach((key, value) -> plain.put(key, plain(value)));
    return plain;
  }

  /** A sub-document is a plain map and a list is a list of plain values, however deep, so a record compares equal both ways. */
  private static Object plain(Object value) {
    if (value instanceof Document nested) {
      return values(nested);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(MongoAuditRecordRepository::plain).toList();
    }
    return value;
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
