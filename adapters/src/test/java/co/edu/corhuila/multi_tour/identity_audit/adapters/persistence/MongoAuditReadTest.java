package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditQuery;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Reading the audit records, newest first, by tenant, period and action, in the database that the db repository builds. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoAuditReadTest {

  private static final Instant BASE = Instant.parse("2026-11-06T10:00:00Z");
  private static final String TENANT = "00000000-0000-4000-8000-0000000000b1";
  private static final String OTHER = "00000000-0000-4000-8000-0000000000b2";
  private static final String ACTOR = "00000000-0000-4000-8000-0000000000a1";

  private ConnectionString url;
  private MongoClient client;
  private MongoAuditRecordRepository repository;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    client.getDatabase(url.getDatabase()).getCollection("audit_records").deleteMany(new Document());
    repository = new MongoAuditRecordRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  /** The record number n, at BASE plus n minutes. */
  private AuditRecord keep(int n, String tenantId, String action, Instant at) {
    AuditRecord record = new AuditRecord(id(n), id(1000 + n), tenantId, ACTOR, action, "accounts/" + id(n), null, null, null, null, null, at);
    repository.append(record);
    return record;
  }

  private static Instant minute(int n) {
    return BASE.plusSeconds(60L * n);
  }

  private static AuditQuery query(String tenantId, Instant from, Instant to, String action, int page, int limit) {
    return new AuditQuery(tenantId, from, to, action, page, limit);
  }

  @Test
  void I_the_records_come_newest_first_with_the_id_as_tie_break_and_page_with_their_total() {
    keep(1, TENANT, "ACCOUNT_BLOCKED", minute(1));
    keep(2, TENANT, "ACCOUNT_BLOCKED", minute(3));
    keep(3, TENANT, "ACCOUNT_BLOCKED", minute(3));
    keep(4, TENANT, "ACCOUNT_BLOCKED", minute(2));

    Page<AuditRecord> first = repository.page(query(null, null, null, null, 1, 3));
    Page<AuditRecord> second = repository.page(query(null, null, null, null, 2, 3));

    assertThat(first.data()).extracting(AuditRecord::auditRecordId).containsExactly(id(3), id(2), id(4));
    assertThat(second.data()).extracting(AuditRecord::auditRecordId).containsExactly(id(1));
    assertThat(first.total()).isEqualTo(4);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(repository.page(query(null, null, null, null, 9, 3)).data()).as("a page past the end is empty, not an error").isEmpty();
  }

  @Test
  void I_the_tenant_the_action_and_the_period_narrow_the_listing_and_the_period_is_closed_at_the_start_and_open_at_the_end() {
    keep(1, TENANT, "ACCOUNT_BLOCKED", minute(1));
    keep(2, TENANT, "TENANT_INACTIVATED", minute(2));
    keep(3, OTHER, "TENANT_INACTIVATED", minute(3));
    keep(4, null, "TENANT_INACTIVATED", minute(4));

    assertThat(repository.page(query(TENANT, null, null, null, 1, 20)).data()).extracting(AuditRecord::auditRecordId)
        .as("a tenant never sees the records of another or of the platform").containsExactly(id(2), id(1));
    assertThat(repository.page(query(null, null, null, "TENANT_INACTIVATED", 1, 20)).data()).extracting(AuditRecord::auditRecordId)
        .as("all tenants, and a record of the platform has no tenant").containsExactly(id(4), id(3), id(2));
    assertThat(repository.page(query(TENANT, null, null, "ACCOUNT_BLOCKED", 1, 20)).data()).extracting(AuditRecord::auditRecordId)
        .containsExactly(id(1));
    assertThat(repository.page(query(null, minute(2), minute(4), null, 1, 20)).data()).extracting(AuditRecord::auditRecordId)
        .as("from is inclusive and to is exclusive").containsExactly(id(3), id(2));
    assertThat(repository.page(query(null, minute(2), minute(2), null, 1, 20)).data()).isEmpty();
    assertThat(repository.page(query(OTHER, null, null, "ACCOUNT_BLOCKED", 1, 20)).total()).isZero();
  }

  @Test
  void I_a_record_comes_back_with_every_field_it_was_kept_with() {
    AuditRecord full = new AuditRecord(id(1), id(1001), TENANT, ACTOR, "TENANT_INACTIVATED", "tenants/" + TENANT, "Contract ended",
        Map.of("tenantStatus", "ACTIVE"), Map.of("tenantStatus", "INACTIVE", "detail", Map.of("by", Map.of("role", "ADMIN")),
            "steps", List.of(Map.of("name", "first"), "second", List.of(1, 2))),
        "ADMIN_PORTAL", "TENANT_LIFECYCLE", minute(1));
    repository.append(full);
    keep(2, null, "ACCOUNT_BLOCKED", minute(2));

    List<AuditRecord> read = repository.page(query(null, null, null, null, 1, 20)).data();

    assertThat(read).hasSize(2);
    assertThat(read.get(1)).isEqualTo(full);
    assertThat(full).as("and the other way round, whatever the depth").isEqualTo(read.get(1));
    assertThat(read.get(1).newValue().get("detail")).as("a sub-document is a plain map").isInstanceOf(java.util.LinkedHashMap.class);
    assertThat(read.get(0).tenantId()).as("a record of the platform has no tenant").isNull();
    assertThat(read.get(0).reason()).isNull();
    assertThat(read.get(0).previousValue()).isNull();
  }

  @Test
  void I_a_page_past_the_end_is_empty_however_large_the_number_and_does_not_fail() {
    keep(1, TENANT, "ACCOUNT_BLOCKED", minute(1));

    assertThat(repository.page(query(null, null, null, null, 20_000_000, 100)).data()).as("an offset that fits an int").isEmpty();
    assertThat(repository.page(query(null, null, null, null, 999_999_999, 100)).data()).as("an offset that does not").isEmpty();
    assertThat(repository.page(query(null, null, null, null, 999_999_999, 100)).total()).isEqualTo(1);
  }

  @Test
  void I_every_shape_of_listing_is_served_by_an_index_with_no_sort_in_memory_with_and_without_a_period() {
    for (int n = 1; n <= 20; n++) {
      keep(n, n % 2 == 0 ? TENANT : OTHER, n % 3 == 0 ? "ACCOUNT_BLOCKED" : "TENANT_INACTIVATED", minute(n));
    }
    Instant from = minute(2);
    Instant to = minute(15);
    List<AuditQuery> shapes = List.of(
        query(TENANT, null, null, null, 1, 20), query(TENANT, from, to, null, 1, 20),
        query(TENANT, null, null, "ACCOUNT_BLOCKED", 1, 20), query(TENANT, from, to, "ACCOUNT_BLOCKED", 1, 20),
        query(null, null, null, null, 1, 20), query(null, from, to, null, 1, 20),
        query(null, null, null, "ACCOUNT_BLOCKED", 1, 20), query(null, from, to, "ACCOUNT_BLOCKED", 1, 20));

    for (AuditQuery shape : shapes) {
      Document filter = new Document();
      if (shape.tenantId() != null) {
        filter.append("tenantId", shape.tenantId());
      }
      if (shape.action() != null) {
        filter.append("action", shape.action());
      }
      if (shape.from() != null) {
        filter.append("recordedAt", new Document("$gte", java.util.Date.from(shape.from())).append("$lt", java.util.Date.from(shape.to())));
      }
      Document explained = client.getDatabase(url.getDatabase()).runCommand(new Document("explain", new Document("find", "audit_records")
          .append("filter", filter).append("sort", new Document("recordedAt", -1).append("_id", -1))).append("verbosity", "queryPlanner"));
      List<String> stages = new java.util.ArrayList<>();
      collect(explained.get("queryPlanner", Document.class).get("winningPlan", Document.class), stages);

      assertThat(stages).as("%s", shape).contains("IXSCAN").doesNotContain("COLLSCAN", "SORT");
    }
  }

  private static void collect(Document plan, List<String> stages) {
    if (plan == null) {
      return;
    }
    stages.add(plan.getString("stage"));
    collect(plan.get("inputStage", Document.class), stages);
    List<?> several = plan.get("inputStages", List.class);
    if (several != null) {
      several.forEach(inner -> collect((Document) inner, stages));
    }
  }
}
