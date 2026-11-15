package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoff;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The cutoffs and the processed event ids, written together in the database that the db repository builds. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoSessionCutoffRepositoryTest {

  private static final Instant T10 = Instant.parse("2026-10-24T10:00:00Z");
  private static final Instant T20 = Instant.parse("2026-10-24T20:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-24T21:00:00Z");
  private static final String ACCOUNT = "00000000-0000-4000-8000-0000000000a1";
  private static final String TENANT = "00000000-0000-4000-8000-0000000000b1";

  private ConnectionString url;
  private MongoClient client;
  private MongoSessionCutoffRepository repository;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    for (String collection : new String[] {"processed_events", "account_session_cutoffs", "audit_records"}) {
      client.getDatabase(url.getDatabase()).getCollection(collection).deleteMany(new Document());
    }
    repository = new MongoSessionCutoffRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static String event(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  /** The record the use case builds for a revocation: its own id, and the event it came from. */
  private static AuditRecord audit(int n) {
    return new AuditRecord("00000000-0000-4000-8000-%012d".formatted(100 + n), event(n), TENANT, "multi-tour-security",
        "ACCOUNT_SESSIONS_REVOKED", "accounts/" + ACCOUNT, null, null, null, null, null, NOW);
  }

  private long audits(String eventId) {
    return client.getDatabase(url.getDatabase()).getCollection("audit_records").countDocuments(new Document("eventId", eventId));
  }

  private long processed(String eventId) {
    return client.getDatabase(url.getDatabase()).getCollection("processed_events").countDocuments(new Document("_id", eventId));
  }

  @Test
  void I_a_first_cutoff_and_its_event_id_are_written_together_and_found_again() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T10, NOW);

    assertThat(repository.record(event(1), null, first, NOW, audit(1))).isTrue();

    assertThat(repository.find(ACCOUNT)).contains(first);
    assertThat(processed(event(1))).isEqualTo(1);
  }

  @Test
  void I_an_account_without_a_tenant_keeps_no_tenant() {
    SessionCutoff platform = new SessionCutoff(ACCOUNT, null, T10, NOW);

    repository.record(event(1), null, platform, NOW, audit(1));

    assertThat(repository.find(ACCOUNT)).contains(platform);
    assertThat(client.getDatabase(url.getDatabase()).getCollection("account_session_cutoffs").find().first().containsKey("tenantId")).isFalse();
  }

  @Test
  void I_a_repeat_of_the_event_id_writes_nothing_and_says_so() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T10, NOW);
    repository.record(event(1), null, first, NOW, audit(1));

    assertThat(repository.record(event(1), first, first.moveTo(T20, NOW), NOW, audit(1))).isFalse();

    assertThat(repository.find(ACCOUNT)).contains(first);
  }

  @Test
  void I_a_later_cutoff_replaces_the_one_it_was_decided_on() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T10, NOW);
    repository.record(event(1), null, first, NOW, audit(1));
    SessionCutoff later = first.moveTo(T20, NOW);

    assertThat(repository.record(event(2), first, later, NOW, audit(2))).isTrue();

    assertThat(repository.find(ACCOUNT)).contains(later);
  }

  @Test
  void I_an_event_that_does_not_move_the_cutoff_is_only_remembered() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T20, NOW);
    repository.record(event(1), null, first, NOW, audit(1));

    assertThat(repository.record(event(2), first, first.moveTo(T10, NOW), NOW, audit(2))).isTrue();

    assertThat(repository.find(ACCOUNT)).contains(first);
    assertThat(processed(event(2))).isEqualTo(1);
  }

  @Test
  void I_a_cutoff_changed_by_someone_else_is_a_race_and_the_event_id_is_not_kept() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T10, NOW);
    repository.record(event(1), null, first, NOW, audit(1));
    client.getDatabase(url.getDatabase()).getCollection("account_session_cutoffs")
        .updateOne(new Document("_id", ACCOUNT), new Document("$set", new Document("revokedFrom", Date.from(T20))));

    assertThatThrownBy(() -> repository.record(event(2), first, first.moveTo(T20.plusSeconds(60), NOW), NOW, audit(2)))
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(processed(event(2))).as("the transaction aborted with the cutoff").isZero();

    // and a first cutoff that someone else created meanwhile is a race too
    assertThatThrownBy(() -> repository.record(event(3), null, new SessionCutoff(ACCOUNT, TENANT, T10, NOW), NOW, audit(3)))
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(processed(event(3))).isZero();
  }

  @Test
  void I_a_missing_cutoff_is_found_as_nothing() {
    assertThat(repository.find(ACCOUNT)).isEmpty();
  }

  @Test
  void I_the_audit_record_is_written_with_the_cutoff_and_with_an_event_that_does_not_move_it() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T20, NOW);
    repository.record(event(1), null, first, NOW, audit(1));
    repository.record(event(2), first, first.moveTo(T10, NOW), NOW, audit(2));

    assertThat(audits(event(1))).isEqualTo(1);
    assertThat(audits(event(2))).as("an event older than the cutoff still leaves its record").isEqualTo(1);
  }

  @Test
  void I_an_event_whose_record_exists_but_whose_id_was_purged_is_a_repeat_and_writes_nothing_else() {
    AuditRecord kept = new AuditRecord("00000000-0000-4000-8000-000000000200", event(5), TENANT, "multi-tour-security",
        "ACCOUNT_SESSIONS_REVOKED", "accounts/" + ACCOUNT, null, null, null, null, null, NOW);
    client.getDatabase(url.getDatabase()).getCollection("audit_records").insertOne(MongoAuditRecordRepository.toDocument(kept));

    assertThat(repository.record(event(5), null, new SessionCutoff(ACCOUNT, TENANT, T10, NOW), NOW, audit(5))).isFalse();

    assertThat(repository.find(ACCOUNT)).as("nothing of the delivery stays").isEmpty();
    assertThat(processed(event(5))).isZero();
    assertThat(audits(event(5))).as("and the record that was there is the only one").isEqualTo(1);
  }

  @Test
  void I_a_collision_of_the_record_id_is_not_a_repeat_and_keeps_nothing() {
    repository.record(event(1), null, new SessionCutoff(ACCOUNT, TENANT, T10, NOW), NOW, audit(1));
    AuditRecord sameId = new AuditRecord(audit(1).auditRecordId(), event(9), TENANT, "multi-tour-security",
        "ACCOUNT_SESSIONS_REVOKED", "accounts/" + ACCOUNT, null, null, null, null, null, NOW);

    assertThatThrownBy(() -> repository.record(event(9), null, new SessionCutoff(
        "00000000-0000-4000-8000-0000000000a2", TENANT, T10, NOW), NOW, sameId))
        .isInstanceOf(com.mongodb.MongoWriteException.class);

    assertThat(processed(event(9))).as("the transaction aborted").isZero();
  }

  @Test
  void I_a_repeat_keeps_no_second_record_and_a_race_keeps_no_record_at_all() {
    SessionCutoff first = new SessionCutoff(ACCOUNT, TENANT, T10, NOW);
    repository.record(event(1), null, first, NOW, audit(1));
    repository.record(event(1), first, first.moveTo(T20, NOW), NOW, audit(1));
    assertThat(audits(event(1))).isEqualTo(1);

    client.getDatabase(url.getDatabase()).getCollection("account_session_cutoffs")
        .updateOne(new Document("_id", ACCOUNT), new Document("$set", new Document("revokedFrom", Date.from(T20))));
    assertThatThrownBy(() -> repository.record(event(2), first, first.moveTo(T20.plusSeconds(60), NOW), NOW, audit(2)))
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(audits(event(2))).as("the transaction aborted with the cutoff").isZero();
  }
}
