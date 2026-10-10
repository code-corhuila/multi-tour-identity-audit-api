package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The events waiting, read and changed in the database that the db repository builds. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoOutboxRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);
  private static final String TENANT = "00000000-0000-4000-8000-0000000000aa";

  private ConnectionString url;
  private MongoClient client;
  private MongoOutboxRepository outbox;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    client.getDatabase(url.getDatabase()).getCollection("outbox_events").deleteMany(new Document());
    outbox = new MongoOutboxRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  private void put(int n, Instant occurredAt, Instant next, String status) {
    Document payload = new Document("tenantId", TENANT).append("tenantStatus", "ACTIVE")
        .append("createdAt", Date.from(NOW)).append("nested", new Document("n", 1));
    client.getDatabase(url.getDatabase()).getCollection("outbox_events").insertOne(
        new Document("_id", id(n)).append("eventType", "TenantCreated").append("eventVersion", 1)
            .append("tenantId", TENANT).append("aggregateId", TENANT).append("actorId", "onboard-tenant")
            .append("payload", payload).append("correlationId", "corr-1").append("occurredAt", Date.from(occurredAt))
            .append("status", status).append("attempts", 0).append("nextAttemptAt", Date.from(next)));
  }

  private Document raw(int n) {
    return client.getDatabase(url.getDatabase()).getCollection("outbox_events").find(new Document("_id", id(n))).first();
  }

  @Test
  void C_the_due_pending_events_come_oldest_first_by_page_with_the_real_total() {
    put(3, NOW.minusSeconds(60), NOW, "PENDING");
    put(1, NOW.minusSeconds(300), NOW.minusSeconds(5), "PENDING");
    put(2, NOW.minusSeconds(120), NOW, "PENDING");
    put(4, NOW.minusSeconds(400), NOW.plusSeconds(1), "PENDING");
    put(5, NOW.minusSeconds(500), NOW, "PUBLISHED");
    put(6, NOW.minusSeconds(600), NOW, "FAILED");
    var first = outbox.due(NOW, 1, 2);
    assertThat(first.data()).extracting(OutboxEvent::eventId).containsExactly(id(1), id(2));
    // an event retried is due when its wait ends: the one due for longest goes first, whatever its age
    put(7, NOW.minusSeconds(10), NOW.minusSeconds(100), "PENDING");
    assertThat(outbox.due(NOW, 1, 1).data()).extracting(OutboxEvent::eventId).containsExactly(id(7));
    client.getDatabase(url.getDatabase()).getCollection("outbox_events").deleteOne(new Document("_id", id(7)));
    assertThat(first.total()).isEqualTo(3);
    assertThat(outbox.due(NOW, 2, 2).data()).extracting(OutboxEvent::eventId).containsExactly(id(3));
    var past = outbox.due(NOW, 9, 2);
    assertThat(past.data()).isEmpty();
    assertThat(past.total()).isEqualTo(3);
    assertThat(outbox.due(NOW, Integer.MAX_VALUE, 100).data()).isEmpty();
  }

  @Test
  void C_events_of_the_same_time_are_ordered_by_id_so_a_page_neither_repeats_nor_skips() {
    for (int n = 1; n <= 5; n++) {
      put(n, NOW, NOW, "PENDING");
    }
    var ids = outbox.due(NOW, 1, 2).data().stream().map(OutboxEvent::eventId).toList();
    ids = new java.util.ArrayList<>(ids);
    ids.addAll(outbox.due(NOW, 2, 2).data().stream().map(OutboxEvent::eventId).toList());
    ids.addAll(outbox.due(NOW, 3, 2).data().stream().map(OutboxEvent::eventId).toList());
    assertThat(ids).containsExactly(id(1), id(2), id(3), id(4), id(5));
  }

  @Test
  void C_an_event_is_read_with_its_payload_and_its_times_as_the_core_knows_them() {
    put(1, NOW, NOW, "PENDING");
    var event = outbox.findById(id(1)).orElseThrow();
    assertThat(event.eventType()).isEqualTo("TenantCreated");
    assertThat(event.actorId()).isEqualTo("onboard-tenant");
    assertThat(event.reason()).isNull();
    assertThat(event.publishedAt()).isNull();
    assertThat(event.lastError()).isNull();
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.payload()).containsEntry("createdAt", NOW).containsEntry("tenantStatus", "ACTIVE");
    assertThat(event.payload().get("nested")).isEqualTo(java.util.Map.of("n", 1));
    assertThat(outbox.findById(id(9))).isEmpty();
  }

  @Test
  void C_publishing_and_a_failed_attempt_are_stored_and_the_database_accepts_them() {
    put(1, NOW, NOW, "PENDING");
    put(2, NOW, NOW, "PENDING");
    var one = outbox.findById(id(1)).orElseThrow();
    outbox.update(one, one.published(NOW.plusSeconds(1)));
    assertThat(raw(1).getString("status")).isEqualTo("PUBLISHED");
    assertThat(raw(1).getDate("publishedAt")).isEqualTo(Date.from(NOW.plusSeconds(1)));
    var two = outbox.findById(id(2)).orElseThrow();
    var failed = two.withFailedAttempt("e".repeat(1000), Instant.parse("2026-10-10T10:00:00.123456789Z"), POLICY);
    outbox.update(two, failed);
    assertThat(raw(2).getInteger("attempts")).isEqualTo(1);
    assertThat(raw(2).getString("lastError")).hasSize(1000);
    assertThat(raw(2).getDate("nextAttemptAt")).isEqualTo(Date.from(NOW.plusSeconds(5)));
    assertThat(outbox.findById(id(2))).contains(failed);
  }

  @Test
  void C_a_change_decided_on_an_event_that_changed_since_is_refused_and_writes_nothing() {
    put(1, NOW, NOW, "PENDING");
    var seen = outbox.findById(id(1)).orElseThrow();
    outbox.update(seen, seen.withFailedAttempt("first", NOW, POLICY));
    assertThatThrownBy(() -> outbox.update(seen, seen.published(NOW))).isInstanceOf(ConcurrentChangeException.class);
    assertThat(raw(1).getString("status")).isEqualTo("PENDING");
    assertThatThrownBy(() -> outbox.update(seen.published(NOW), seen)).isInstanceOf(ConcurrentChangeException.class);
    client.getDatabase(url.getDatabase()).getCollection("outbox_events").deleteMany(new Document());
    assertThatThrownBy(() -> outbox.update(seen, seen.published(NOW))).isInstanceOf(ConcurrentChangeException.class);
  }
}
