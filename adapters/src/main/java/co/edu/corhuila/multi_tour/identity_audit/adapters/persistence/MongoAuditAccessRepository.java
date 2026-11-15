package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditAccessSetting;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditAccessSettings;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;

/**
 * The audit access of the optional roles in {@code audit_access_settings}, one document per tenant and role, which the unique
 * index of {@code identity-audit-db} guarantees. A change and its audit record go in one transaction.
 */
public final class MongoAuditAccessRepository implements AuditAccessSettings {

  /** A committed write must survive a failover, whatever the URI of the connection says. */
  private static final TransactionOptions DURABLE =
      TransactionOptions.builder().readConcern(ReadConcern.MAJORITY).writeConcern(WriteConcern.MAJORITY).build();

  private final MongoClient client;
  private final String database;

  public MongoAuditAccessRepository(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  private MongoCollection<Document> settings() {
    return client.getDatabase(database).getCollection("audit_access_settings");
  }

  private MongoCollection<Document> audits() {
    return client.getDatabase(database).getCollection("audit_records");
  }

  @Override
  public List<AuditAccessSetting> find(String tenantId) {
    Map<String, Document> stored = new HashMap<>();
    ReadLimit.of(settings().find(eq("tenantId", tenantId))).forEach(d -> stored.put(d.getString("role"), d));
    return AuditAccessSetting.OPTIONAL_ROLES.stream().map(role -> {
      Document document = stored.get(role);
      return document == null
          ? AuditAccessSetting.neverSet(role)
          : new AuditAccessSetting(role, document.getBoolean("enabled"), document.getString("changedBy"),
              document.getDate("changedAt").toInstant());
    }).toList();
  }

  @Override
  public void change(String tenantId, AuditAccessSetting expected, AuditAccessSetting next, AuditRecord audit) {
    try (ClientSession session = client.startSession()) {
      session.withTransaction(() -> {
        if (!expected.wasSet()) {
          try {
            settings().insertOne(session, new Document("_id", UUID.randomUUID().toString()).append("tenantId", tenantId)
                .append("role", next.role()).append("enabled", next.enabled()).append("changedBy", next.changedBy())
                .append("changedAt", Date.from(next.changedAt())));
          } catch (MongoWriteException e) {
            // somebody set it first: the unique index of tenant and role refuses a second one
            throw e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY ? lost(tenantId, next) : e;
          }
        } else if (settings().updateOne(session,
                and(eq("tenantId", tenantId), eq("role", next.role()), eq("enabled", expected.enabled()),
                    eq("changedAt", Date.from(expected.changedAt()))),
                combine(set("enabled", next.enabled()), set("changedBy", next.changedBy()), set("changedAt", Date.from(next.changedAt()))))
            .getMatchedCount() == 0) {
          // the setting the decision was made on is gone: a request that raced finds nothing to replace
          throw lost(tenantId, next);
        }
        audits().insertOne(session, MongoAuditRecordRepository.toDocument(audit));
        return null;
      }, DURABLE);
    }
  }

  private static ConcurrentChangeException lost(String tenantId, AuditAccessSetting next) {
    return new ConcurrentChangeException("audit access", tenantId + "/" + next.role());
  }
}
