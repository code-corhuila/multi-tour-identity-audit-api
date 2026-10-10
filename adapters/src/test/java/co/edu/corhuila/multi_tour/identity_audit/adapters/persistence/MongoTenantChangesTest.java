package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailedStep;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEventType;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Changing a tenant with its events, and listing, against the database that the db repository builds. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTenantChangesTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final Instant LATER = NOW.plusSeconds(60);
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private ConnectionString url;
  private MongoClient client;
  private MongoTenantRepository tenants;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(url.getDatabase()).getCollection(collection).deleteMany(new Document());
    }
    tenants = new MongoTenantRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private long count(String collection) {
    return client.getDatabase(url.getDatabase()).getCollection(collection).countDocuments();
  }

  private Document outbox() {
    return client.getDatabase(url.getDatabase()).getCollection("outbox_events").find().first();
  }

  private Tenant stored(int n, String identifier) {
    Tenant tenant = Tenant.registerPending(
        "00000000-0000-4000-8000-%012d".formatted(n), identifier, "Tenant " + n, "America/Bogota", "CO",
        NOW.plusSeconds(60L * n));
    tenants.insert(tenant, new RepeatKey("registerPendingTenant", "key-%07d".formatted(n)));
    return tenant;
  }

  private static TenantEvent event(int n, TenantEventType type, Tenant tenant, Instant at) {
    return new TenantEvent("00000000-0000-4000-8000-0000000e%04d".formatted(n), type, tenant, "onboard-tenant", "corr-" + n, at);
  }

  @Test
  void I_tenant_and_event_are_written_together_and_the_event_waits_to_be_delivered() {
    Tenant pending = stored(1, "acme-tours");
    Tenant done = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, LATER);
    tenants.update(pending, done, List.of(event(1, TenantEventType.TENANT_CREATED, done, LATER)));
    assertThat(tenants.findById(pending.tenantId())).contains(done);
    assertThat(count("outbox_events")).isEqualTo(1);
    Document event = outbox();
    assertThat(event.getString("eventType")).isEqualTo("TenantCreated");
    assertThat(event.getString("status")).isEqualTo("PENDING");
    assertThat(event.getInteger("attempts")).isZero();
    assertThat(event.getInteger("eventVersion")).isEqualTo(1);
    assertThat(event.getString("aggregateId")).isEqualTo(pending.tenantId());
    assertThat(event.getString("tenantId")).isEqualTo(pending.tenantId());
    assertThat(event.getString("actorId")).isEqualTo("onboard-tenant");
    assertThat(event.getString("correlationId")).isEqualTo("corr-1");
    assertThat(event.getDate("nextAttemptAt")).isEqualTo(event.getDate("occurredAt"));
    assertThat(event.get("payload", Document.class).getString("tenantStatus")).isEqualTo("ACTIVE");
    assertThat(event.get("payload", Document.class).getString("identifier")).isEqualTo("acme-tours");
  }

  @Test
  void I_every_status_with_its_fields_goes_through_the_validator_and_comes_back_equal() {
    Tenant pending = stored(1, "acme-tours");
    Tenant active = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, LATER);
    tenants.update(pending, active, List.of(event(1, TenantEventType.TENANT_CREATED, active, LATER)));
    Tenant inactive = active.inactivate("platform-admin", "unpaid", LATER.plusSeconds(1));
    tenants.update(active, inactive, List.of(event(2, TenantEventType.TENANT_INACTIVATED, inactive, inactive.updatedAt())));
    assertThat(tenants.findById(pending.tenantId())).contains(inactive);
    Tenant again = inactive.reactivate("platform-admin", "paid", LATER.plusSeconds(2));
    tenants.update(inactive, again, List.of(event(3, TenantEventType.TENANT_REACTIVATED, again, again.updatedAt())));
    assertThat(tenants.findById(pending.tenantId())).contains(again);
    Tenant other = stored(2, "other-one");
    Tenant failed = other.failOnboarding(FailedStep.CREATE_ACCOUNT, LATER);
    tenants.update(other, failed, List.of());
    assertThat(tenants.findById(other.tenantId())).contains(failed);
    assertThat(count("outbox_events")).isEqualTo(3);
  }

  @Test
  void I_a_change_decided_on_an_old_state_is_refused_and_leaves_the_tenant_and_no_event() {
    Tenant pending = stored(1, "acme-tours");
    Tenant done = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, LATER);
    tenants.update(pending, done, List.of(event(1, TenantEventType.TENANT_CREATED, done, LATER)));
    Tenant failed = pending.failOnboarding(FailedStep.APPLY_STATUS, LATER.plusSeconds(1));
    assertThatThrownBy(() -> tenants.update(pending, failed, List.of(event(2, TenantEventType.TENANT_CREATED, failed, LATER))))
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(tenants.findById(pending.tenantId())).contains(done);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void I_a_failed_write_of_the_event_leaves_the_tenant_as_it_was() {
    Tenant pending = stored(1, "acme-tours");
    Tenant done = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, LATER);
    TenantEvent taken = event(1, TenantEventType.TENANT_CREATED, done, LATER);
    client.getDatabase(url.getDatabase()).getCollection("outbox_events").insertOne(
        new Document("_id", taken.eventId()).append("eventType", "TenantCreated").append("eventVersion", 1)
            .append("aggregateId", pending.tenantId()).append("payload", new Document())
            .append("correlationId", "c").append("occurredAt", Date.from(LATER)).append("status", "PENDING")
            .append("attempts", 0).append("nextAttemptAt", Date.from(LATER)));
    assertThatThrownBy(() -> tenants.update(pending, done, List.of(taken))).isInstanceOf(RuntimeException.class);
    assertThat(tenants.findById(pending.tenantId())).contains(pending);
  }

  @Test
  void I_two_changes_decided_on_the_same_state_at_once_one_wins_and_leaves_one_event() throws Exception {
    Tenant pending = stored(1, "acme-tours");
    Tenant active = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, LATER);
    tenants.update(pending, active, List.of());
    int requests = 4;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Throwable>> outcomes = new ArrayList<>();
    for (int n = 1; n <= requests; n++) {
      int id = n;
      outcomes.add(pool.submit(() -> {
        start.await();
        Tenant off = active.inactivate("admin-" + id, "reason " + id, LATER.plusSeconds(id));
        try {
          tenants.update(active, off, List.of(event(10 + id, TenantEventType.TENANT_INACTIVATED, off, off.updatedAt())));
          return null;
        } catch (RuntimeException e) {
          return e;
        }
      }));
    }
    start.countDown();
    List<Throwable> failures = new ArrayList<>();
    for (Future<Throwable> outcome : outcomes) {
      if (outcome.get() != null) {
        failures.add(outcome.get());
      }
    }
    pool.shutdown();
    assertThat(failures).hasSize(requests - 1).allMatch(f -> f instanceof ConcurrentChangeException);
    assertThat(count("outbox_events")).isEqualTo(1);
    assertThat(tenants.findById(pending.tenantId()).orElseThrow().status()).isEqualTo(TenantStatus.INACTIVE);
  }

  @Test
  void I_the_list_answers_the_newest_first_in_pages_filtered_by_status_and_beyond_the_last() {
    for (int n = 1; n <= 5; n++) {
      Tenant tenant = stored(n, "tenant-" + n);
      if (n % 2 == 1) {
        tenants.update(tenant, tenant.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, tenant.createdAt()), List.of());
      }
    }
    Page<Tenant> first = tenants.list(null, 1, 2);
    assertThat(first.data()).extracting(Tenant::identifier).containsExactly("tenant-5", "tenant-4");
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(tenants.list(null, 3, 2).data()).extracting(Tenant::identifier).containsExactly("tenant-1");
    assertThat(tenants.list(TenantStatus.ACTIVE, 1, 20).data()).extracting(Tenant::identifier)
        .containsExactly("tenant-5", "tenant-3", "tenant-1");
    assertThat(tenants.list(TenantStatus.PENDING, 1, 20).total()).isEqualTo(2);
    Page<Tenant> far = tenants.list(null, Integer.MAX_VALUE, 100);
    assertThat(far.data()).isEmpty();
    assertThat(far.total()).isEqualTo(5);
  }
}
