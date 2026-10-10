package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;

import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * The events waiting in {@code outbox_events}. The owner changes one document at a time, so a write needs no
 * transaction: the filter holds the event as it was decided on, and a request that raced finds nothing to replace.
 */
public final class MongoOutboxRepository implements OutboxRepository {

  private final MongoClient client;
  private final String database;

  public MongoOutboxRepository(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  private MongoCollection<Document> events() {
    return client.getDatabase(database).getCollection("outbox_events");
  }

  @Override
  public Page<OutboxEvent> due(Instant now, int page, int limit) {
    Bson filter = and(eq("status", "PENDING"), lte("nextAttemptAt", Date.from(now)));
    // the order of idx_outbox_events_pending (status, nextAttemptAt, occurredAt), so that the sort reads the index
    long total = events().countDocuments(filter);
    long offset = (long) (page - 1) * limit;
    List<OutboxEvent> data = offset > Integer.MAX_VALUE
        ? List.of()
        : events().find(filter).sort(Sorts.orderBy(Sorts.ascending("nextAttemptAt"), Sorts.ascending("occurredAt"), Sorts.ascending("_id")))
            .skip((int) offset).limit(limit).map(MongoOutboxRepository::toEvent).into(new ArrayList<>());
    return new Page<>(data, page, limit, total);
  }

  @Override
  public Optional<OutboxEvent> findById(String eventId) {
    return Optional.ofNullable(events().find(eq("_id", eventId)).first()).map(MongoOutboxRepository::toEvent);
  }

  @Override
  public void update(OutboxEvent expected, OutboxEvent next) {
    Bson stillIs = and(
        eq("_id", expected.eventId()), eq("status", expected.status().name()), eq("attempts", expected.attempts()),
        eq("publishedAt", date(expected.publishedAt())), eq("lastError", expected.lastError()),
        eq("nextAttemptAt", date(expected.nextAttemptAt())));
    if (events().replaceOne(stillIs, toDocument(next)).getMatchedCount() == 0) {
      throw new ConcurrentChangeException(next.eventId());
    }
  }

  private static Date date(Instant instant) {
    return instant == null ? null : Date.from(instant);
  }

  /** Only what the validator of the database lets in: an absent value is left out, not stored as null. */
  private static Document toDocument(OutboxEvent e) {
    Document d = new Document("_id", e.eventId()).append("eventType", e.eventType()).append("eventVersion", e.eventVersion())
        .append("aggregateId", e.aggregateId()).append("payload", new Document(toStored(e.payload())))
        .append("correlationId", e.correlationId()).append("occurredAt", date(e.occurredAt()))
        .append("status", e.status().name()).append("attempts", e.attempts()).append("nextAttemptAt", date(e.nextAttemptAt()));
    putIfPresent(d, "tenantId", e.tenantId());
    putIfPresent(d, "actorId", e.actorId());
    putIfPresent(d, "reason", e.reason());
    putIfPresent(d, "publishedAt", date(e.publishedAt()));
    putIfPresent(d, "lastError", e.lastError());
    return d;
  }

  private static void putIfPresent(Document d, String key, Object value) {
    if (value != null) {
      d.append(key, value);
    }
  }

  private static OutboxEvent toEvent(Document d) {
    Date published = d.getDate("publishedAt");
    return new OutboxEvent(
        d.getString("_id"), d.getString("eventType"), ((Number) d.get("eventVersion")).intValue(), d.getString("tenantId"),
        d.getString("aggregateId"), d.getString("actorId"), d.getString("reason"), toCore(d.get("payload", Document.class)),
        d.getString("correlationId"), d.getDate("occurredAt").toInstant(), published == null ? null : published.toInstant(),
        OutboxEvent.Status.valueOf(d.getString("status")), ((Number) d.get("attempts")).intValue(), d.getString("lastError"),
        d.getDate("nextAttemptAt").toInstant());
  }

  /** The payload as plain values, with the dates as instants. */
  private static Map<String, Object> toCore(Document document) {
    Map<String, Object> map = new LinkedHashMap<>();
    document.forEach((key, value) -> map.put(key, value instanceof Date date ? date.toInstant()
        : value instanceof Document nested ? toCore(nested) : value));
    return map;
  }

  /** Back to what the database stores: the instants as dates. */
  private static Map<String, Object> toStored(Map<String, Object> map) {
    Map<String, Object> stored = new LinkedHashMap<>();
    map.forEach((key, value) -> stored.put(key, value instanceof Instant instant ? Date.from(instant)
        : value instanceof Map<?, ?> nested ? new Document(toStored(castToMap(nested))) : value));
    return stored;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
