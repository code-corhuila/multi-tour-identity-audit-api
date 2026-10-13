package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.VisualIdentity;
import com.mongodb.ConnectionString;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import java.time.Instant;
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

/** The visual identity of a tenant, against the database that the db repository builds (validator included). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoVisualIdentityTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final VisualIdentity COLORS = new VisualIdentity("#146B5D", "#0F564B");

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

  private Tenant active(int n, String identifier) {
    Tenant pending = Tenant.registerPending(
        "00000000-0000-4000-8000-%012d".formatted(n), identifier, "Tenant " + n, "America/Bogota", "CO", NOW);
    tenants.insert(pending, new RepeatKey("registerPendingTenant", "key-%07d".formatted(n)));
    Tenant active = pending.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, NOW.plusSeconds(1));
    tenants.update(pending, active, List.of());
    return active;
  }

  @Test
  void I_visual_identity_is_stored_and_read_back_with_the_time_of_the_change() {
    Tenant tenant = active(1, "acme-tours");
    Instant at = NOW.plusSeconds(60);
    assertThat(tenants.saveVisualIdentity(tenant.tenantId(), COLORS, at)).isTrue();
    Tenant read = tenants.findById(tenant.tenantId()).orElseThrow();
    assertThat(read.visualIdentity()).isEqualTo(COLORS);
    assertThat(read.updatedAt()).isEqualTo(at);
    assertThat(read.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(tenants.findByIdentifier("acme-tours").orElseThrow().visualIdentity()).isEqualTo(COLORS);
  }

  @Test
  void I_a_tenant_that_never_set_one_reads_null() {
    assertThat(tenants.findById(active(1, "acme-tours").tenantId()).orElseThrow().visualIdentity()).isNull();
  }

  @Test
  void I_a_status_change_after_a_save_keeps_the_colors() {
    Tenant tenant = active(1, "acme-tours");
    tenants.saveVisualIdentity(tenant.tenantId(), COLORS, NOW.plusSeconds(60));
    Tenant saved = tenants.findById(tenant.tenantId()).orElseThrow();
    Tenant off = saved.inactivate("platform-admin", "unpaid", NOW.plusSeconds(120));
    tenants.update(saved, off, List.of());
    Tenant read = tenants.findById(tenant.tenantId()).orElseThrow();
    assertThat(read.status()).isEqualTo(TenantStatus.INACTIVE);
    assertThat(read.visualIdentity()).isEqualTo(COLORS);
  }

  @Test
  void I_a_save_on_an_inactive_pending_or_unknown_tenant_returns_false_and_changes_nothing() {
    Tenant tenant = active(1, "acme-tours");
    Tenant off = tenant.inactivate("platform-admin", "unpaid", NOW.plusSeconds(10));
    tenants.update(tenant, off, List.of());
    Tenant pending = Tenant.registerPending(
        "00000000-0000-4000-8000-000000000002", "other-one", "Tenant 2", "America/Bogota", "CO", NOW);
    tenants.insert(pending, new RepeatKey("registerPendingTenant", "key-0000002"));
    assertThat(tenants.saveVisualIdentity(off.tenantId(), COLORS, NOW.plusSeconds(60))).isFalse();
    assertThat(tenants.saveVisualIdentity(pending.tenantId(), COLORS, NOW.plusSeconds(60))).isFalse();
    assertThat(tenants.saveVisualIdentity("00000000-0000-4000-8000-0000000000ff", COLORS, NOW)).isFalse();
    assertThat(tenants.findById(off.tenantId())).contains(off);
    assertThat(tenants.findById(pending.tenantId())).contains(pending);
  }

  @Test
  void I_only_the_target_tenant_changes() {
    Tenant first = active(1, "acme-tours");
    Tenant second = active(2, "other-tours");
    assertThat(tenants.saveVisualIdentity(first.tenantId(), COLORS, NOW.plusSeconds(60))).isTrue();
    assertThat(tenants.findById(second.tenantId())).contains(second);
    assertThat(tenants.findById(first.tenantId()).orElseThrow().visualIdentity()).isEqualTo(COLORS);
  }

  @Test
  void I_a_shape_the_validator_refuses_is_refused_and_leaves_the_document() {
    Tenant tenant = active(1, "acme-tours");
    var collection = client.getDatabase(url.getDatabase()).getCollection("tenants");
    for (Document bad : List.of(
        new Document("primaryColor", "red").append("secondaryColor", "#000000"),
        new Document("primaryColor", "#000000"),
        new Document("primaryColor", "#000000").append("secondaryColor", "#FFFFFF").append("logo", "x"))) {
      assertThatThrownBy(() -> collection.updateOne(
              Filters.eq("_id", tenant.tenantId()), Updates.set("visualIdentity", bad)))
          .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getCode()).isEqualTo(121));
    }
    assertThat(tenants.findById(tenant.tenantId())).contains(tenant);
  }

  @Test
  void I_a_status_change_and_a_save_at_once_one_wins_and_none_is_lost() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int n = 1; n <= 10; n++) {
        Tenant tenant = active(n, "tenant-" + n);
        Tenant off = tenant.inactivate("platform-admin", "unpaid", NOW.plusSeconds(100));
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> save = pool.submit(() -> {
          start.await();
          return tenants.saveVisualIdentity(tenant.tenantId(), COLORS, NOW.plusSeconds(200));
        });
        Future<Boolean> change = pool.submit(() -> {
          start.await();
          try {
            tenants.update(tenant, off, List.of());
            return true;
          } catch (ConcurrentChangeException lost) {
            return false;
          }
        });
        start.countDown();
        boolean saved = save.get();
        boolean changed = change.get();
        Tenant read = tenants.findById(tenant.tenantId()).orElseThrow();
        assertThat(saved ^ changed).as("exactly one wins, round %d", n).isTrue();
        if (saved) {
          assertThat(read.status()).isEqualTo(TenantStatus.ACTIVE);
          assertThat(read.visualIdentity()).isEqualTo(COLORS);
        } else {
          assertThat(read.status()).isEqualTo(TenantStatus.INACTIVE);
          assertThat(read.visualIdentity()).isNull();
        }
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void I_a_document_written_with_lower_case_colors_can_still_change_status() {
    Tenant tenant = active(1, "acme-tours");
    client.getDatabase(url.getDatabase()).getCollection("tenants").updateOne(
        Filters.eq("_id", tenant.tenantId()),
        Updates.set("visualIdentity", new Document("primaryColor", "#aabbcc").append("secondaryColor", "#ddeeff")));
    Tenant read = tenants.findById(tenant.tenantId()).orElseThrow();
    Tenant off = read.inactivate("platform-admin", "unpaid", NOW.plusSeconds(120));
    tenants.update(read, off, List.of());
    assertThat(tenants.findById(tenant.tenantId()).orElseThrow().status()).isEqualTo(TenantStatus.INACTIVE);
  }
}