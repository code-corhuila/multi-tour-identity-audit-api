package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoff;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoffs;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.bson.codecs.configuration.CodecConfigurationException;

/**
 * The cutoffs in {@code account_session_cutoffs}, the event ids in {@code processed_events} and the audit record of the
 * event in {@code audit_records}. The three go in one transaction, so that a repeat never sees the id without its effect
 * and an effect never goes without its evidence.
 */
public final class MongoSessionCutoffRepository implements SessionCutoffs {

  /** A committed write must survive a failover, whatever the URI of the connection says. */
  private static final TransactionOptions DURABLE =
      TransactionOptions.builder().readConcern(ReadConcern.MAJORITY).writeConcern(WriteConcern.MAJORITY).build();

  /** The unique index of identity-audit-db that holds one audit record per event. */
  private static final String EVENT_ID_INDEX = "ux_audit_records_event_id";

  private final MongoClient client;
  private final String database;

  public MongoSessionCutoffRepository(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  private MongoCollection<Document> cutoffs() {
    return client.getDatabase(database).getCollection("account_session_cutoffs");
  }

  private MongoCollection<Document> processed() {
    return client.getDatabase(database).getCollection("processed_events");
  }

  /** Thrown inside the transaction so that it aborts; the caller turns it into "already applied". */
  private static final class AlreadyApplied extends RuntimeException {
    AlreadyApplied() {
      super(null, null, false, false);
    }
  }

  private MongoCollection<Document> audits() {
    return client.getDatabase(database).getCollection("audit_records");
  }

  @Override
  public Optional<SessionCutoff> find(String accountId) {
    Document stored = ReadLimit.of(cutoffs().find(eq("_id", accountId))).first();
    return Optional.ofNullable(stored).map(MongoSessionCutoffRepository::toCutoff);
  }

  @Override
  public boolean record(String eventId, SessionCutoff expected, SessionCutoff next, Instant now, AuditRecord audit) {
    try (ClientSession session = client.startSession()) {
      session.withTransaction(() -> {
        try {
          processed().insertOne(session, new Document("_id", eventId).append("processedAt", Date.from(now)));
        } catch (MongoWriteException e) {
          throw e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY ? new AlreadyApplied() : e;
        }
        if (expected == null) {
          try {
            cutoffs().insertOne(session, toDocument(next));
          } catch (MongoWriteException e) {
            throw e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                ? new ConcurrentChangeException("account session cutoff", next.accountId())
                : e;
          }
        } else if (!next.equals(expected)) {
          // the cutoff as it was decided on: a request that raced finds nothing to replace
          long replaced = cutoffs()
              .replaceOne(session, and(eq("_id", next.accountId()), eq("revokedFrom", Date.from(expected.revokedFrom()))), toDocument(next))
              .getMatchedCount();
          if (replaced == 0) {
            throw new ConcurrentChangeException("account session cutoff", next.accountId());
          }
        }
        try {
          audits().insertOne(session, MongoAuditRecordRepository.toDocument(audit));
        } catch (MongoWriteException e) {
          // The event already has a record: either another delivery got there first, or its id was purged from
          // processed_events (job 6) while the audit, which is never purged, kept it. Only the index of the event id means
          // that; a collision of the record id is not "already applied" and must not hide that the cutoff was not kept.
          throw e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY && e.getError().getMessage().contains(EVENT_ID_INDEX)
              ? new AlreadyApplied() : e;
        }
        return null;
      }, DURABLE);
      return true;
    } catch (AlreadyApplied repeated) {
      return false;
    } catch (CodecConfigurationException unsupported) {
      // a value of the payload the driver cannot encode aborts the transaction: neither the cutoff nor the id is kept
      throw new InvalidDataException("payload", "holds a value that cannot be kept");
    }
  }

  private static Document toDocument(SessionCutoff cutoff) {
    Document document = new Document("_id", cutoff.accountId());
    if (cutoff.tenantId() != null) {
      document.append("tenantId", cutoff.tenantId());
    }
    return document.append("revokedFrom", Date.from(cutoff.revokedFrom())).append("updatedAt", Date.from(cutoff.updatedAt()));
  }

  private static SessionCutoff toCutoff(Document document) {
    return new SessionCutoff(document.getString("_id"), document.getString("tenantId"),
        document.getDate("revokedFrom").toInstant(), document.getDate("updatedAt").toInstant());
  }
}
