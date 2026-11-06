package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditAccessSetting;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The audit access of the optional roles and the record of each change, written together in the database that the db repository builds. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoAuditAccessRepositoryTest {

  private static final Instant T1 = Instant.parse("2026-11-06T10:00:00.123Z");
  private static final Instant T2 = Instant.parse("2026-11-06T11:00:00.456Z");
  private static final String TENANT = "00000000-0000-4000-8000-0000000000b1";
  private static final String OTHER_TENANT = "00000000-0000-4000-8000-0000000000b2";
  private static final String ADMIN = "00000000-0000-4000-8000-0000000000a1";

  private ConnectionString url;
  private MongoClient client;
  private MongoAuditAccessRepository repository;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    for (String collection : new String[] {"audit_access_settings", "audit_records"}) {
      client.getDatabase(url.getDatabase()).getCollection(collection).deleteMany(new Document());
    }
    repository = new MongoAuditAccessRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  private static AuditAccessSetting enabled(String role, Instant at) {
    return new AuditAccessSetting(role, true, ADMIN, at);
  }

  private static AuditRecord audit(int n, String eventId, AuditAccessSetting next) {
    return new AuditRecord(id(100 + n), eventId, TENANT, ADMIN, next.enabled() ? "AUDIT_ACCESS_ENABLED" : "AUDIT_ACCESS_DISABLED",
        "tenants/" + TENANT + "/audit-access/" + next.role(), null, Map.of("role", next.role(), "enabled", !next.enabled()),
        Map.of("role", next.role(), "enabled", next.enabled()), null, null, next.changedAt());
  }

  private long audits() {
    return client.getDatabase(url.getDatabase()).getCollection("audit_records").countDocuments();
  }

  private Document stored(String tenantId, String role) {
    return client.getDatabase(url.getDatabase()).getCollection("audit_access_settings")
        .find(new Document("tenantId", tenantId).append("role", role)).first();
  }

  @Test
  void I_a_tenant_without_settings_has_the_three_roles_off_in_order() {
    assertThat(repository.find(TENANT)).containsExactly(
        AuditAccessSetting.neverSet("MANAGER"), AuditAccessSetting.neverSet("ACCOUNTANT"), AuditAccessSetting.neverSet("ANALYST"));
  }

  @Test
  void I_a_first_setting_and_its_record_are_written_together_found_again_and_replaced_by_the_next() {
    AuditAccessSetting first = enabled("ANALYST", T1);
    repository.change(TENANT, AuditAccessSetting.neverSet("ANALYST"), first, audit(1, id(1), first));

    assertThat(repository.find(TENANT)).containsExactly(
        AuditAccessSetting.neverSet("MANAGER"), AuditAccessSetting.neverSet("ACCOUNTANT"), first);
    assertThat(audits()).isEqualTo(1);

    AuditAccessSetting off = new AuditAccessSetting("ANALYST", false, ADMIN, T2);
    repository.change(TENANT, first, off, audit(2, id(2), off));

    assertThat(repository.find(TENANT)).contains(off);
    assertThat(audits()).isEqualTo(2);
    assertThat(repository.find(OTHER_TENANT)).as("another tenant is not touched").allMatch(s -> !s.wasSet());
  }

  @Test
  void I_a_record_that_cannot_be_kept_leaves_the_setting_as_it_was_for_a_first_setting_and_for_a_replacement() {
    AuditAccessSetting first = enabled("MANAGER", T1);
    // a record of that very event is already there, so the unique index of the event id refuses the new one
    client.getDatabase(url.getDatabase()).getCollection("audit_records")
        .insertOne(MongoAuditRecordRepository.toDocument(audit(9, id(7), first)));

    assertThatThrownBy(() -> repository.change(TENANT, AuditAccessSetting.neverSet("MANAGER"), first, audit(1, id(7), first)))
        .isInstanceOf(MongoException.class);
    assertThat(stored(TENANT, "MANAGER")).as("a first setting is not left behind").isNull();

    repository.change(TENANT, AuditAccessSetting.neverSet("MANAGER"), first, audit(2, id(8), first));
    AuditAccessSetting off = new AuditAccessSetting("MANAGER", false, ADMIN, T2);
    assertThatThrownBy(() -> repository.change(TENANT, first, off, audit(3, id(7), off))).isInstanceOf(MongoException.class);
    assertThat(repository.find(TENANT)).as("a replacement is not applied").contains(first);
    assertThat(audits()).as("the one that was there and the one of the first setting").isEqualTo(2);
  }

  @Test
  void I_a_setting_created_by_someone_else_meanwhile_is_a_race_and_writes_no_record() {
    AuditAccessSetting theirs = new AuditAccessSetting("ACCOUNTANT", true, ADMIN, T1);
    repository.change(TENANT, AuditAccessSetting.neverSet("ACCOUNTANT"), theirs, audit(1, id(1), theirs));
    AuditAccessSetting mine = new AuditAccessSetting("ACCOUNTANT", true, ADMIN, T2);

    assertThatThrownBy(() -> repository.change(TENANT, AuditAccessSetting.neverSet("ACCOUNTANT"), mine, audit(2, id(2), mine)))
        .isInstanceOf(ConcurrentChangeException.class);

    assertThat(repository.find(TENANT)).contains(theirs);
    assertThat(audits()).isEqualTo(1);
  }

  @Test
  void I_a_setting_changed_by_someone_else_meanwhile_is_a_race_and_writes_no_record() {
    AuditAccessSetting first = enabled("MANAGER", T1);
    repository.change(TENANT, AuditAccessSetting.neverSet("MANAGER"), first, audit(1, id(1), first));
    client.getDatabase(url.getDatabase()).getCollection("audit_access_settings").updateOne(
        new Document("tenantId", TENANT).append("role", "MANAGER"),
        new Document("$set", new Document("enabled", false).append("changedAt", Date.from(T2))));
    AuditAccessSetting off = new AuditAccessSetting("MANAGER", false, ADMIN, T2.plusSeconds(60));

    assertThatThrownBy(() -> repository.change(TENANT, first, off, audit(2, id(2), off))).isInstanceOf(ConcurrentChangeException.class);

    assertThat(stored(TENANT, "MANAGER").getDate("changedAt")).isEqualTo(Date.from(T2));
    assertThat(audits()).isEqualTo(1);
  }
}
