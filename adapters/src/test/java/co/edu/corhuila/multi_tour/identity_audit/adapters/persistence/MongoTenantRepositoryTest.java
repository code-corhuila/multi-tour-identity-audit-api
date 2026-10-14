package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdentifierTakenException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKeyUsedException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.ArrayList;
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

/** The adapter against the database that {@code multi-tour-identity-audit-db} builds, validators included. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTenantRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");

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

  private static Tenant tenant(int n, String identifier) {
    return Tenant.registerPending(
        "00000000-0000-4000-8000-%012d".formatted(n), identifier, "Tenant " + n, "America/Bogota", "CO", NOW);
  }

  private static RepeatKey key(String value) {
    return new RepeatKey("registerPendingTenant", value);
  }

  @Test
  void I_a_new_tenant_is_stored_with_its_key_and_found_by_id_identifier_and_key() {
    Tenant tenant = tenant(1, "acme-tours");
    tenants.insert(tenant, key("key-0000001"));
    assertThat(tenants.findById(tenant.tenantId())).contains(tenant);
    assertThat(tenants.findByIdentifier("acme-tours")).contains(tenant);
    assertThat(tenants.findByRepeatKey(key("key-0000001"))).contains(tenant);
    assertThat(tenants.findById("00000000-0000-4000-8000-0000000000ff")).isEmpty();
    assertThat(tenants.findByRepeatKey(key("key-0000002"))).isEmpty();
    assertThat(tenants.findByRepeatKey(new RepeatKey("otherOperation", "key-0000001"))).isEmpty();
  }

  @Test
  void I_a_failed_tenant_does_not_hold_the_identifier() {
    tenants.insert(tenant(1, "acme-tours"), key("key-0000001"));
    client.getDatabase(url.getDatabase()).getCollection("tenants")
        .updateOne(new Document("identifier", "acme-tours"),
            new Document("$set", new Document("tenantStatus", "FAILED").append("failedStep", "CREATE_ACCOUNT")));
    assertThat(tenants.findByIdentifier("acme-tours")).isEmpty();
    tenants.insert(tenant(2, "acme-tours"), key("key-0000002"));
    assertThat(tenants.findByIdentifier("acme-tours")).map(Tenant::tenantId)
        .contains("00000000-0000-4000-8000-000000000002");
  }

  @Test
  void I_a_second_creation_with_the_same_key_is_refused_and_stores_nothing() {
    tenants.insert(tenant(1, "acme-tours"), key("key-0000001"));
    assertThatThrownBy(() -> tenants.insert(tenant(2, "other-one"), key("key-0000001")))
        .isInstanceOf(RepeatKeyUsedException.class);
    assertThat(tenants.findById("00000000-0000-4000-8000-000000000002")).isEmpty();
    assertThat(count("tenants")).isEqualTo(1);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }

  @Test
  void I_a_second_creation_with_a_live_identifier_is_refused_and_leaves_no_key() {
    tenants.insert(tenant(1, "acme-tours"), key("key-0000001"));
    assertThatThrownBy(() -> tenants.insert(tenant(2, "acme-tours"), key("key-0000002")))
        .isInstanceOf(IdentifierTakenException.class);
    assertThat(count("tenants")).isEqualTo(1);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }

  @Test
  void I_when_both_conflict_the_repeat_key_is_the_one_reported() {
    tenants.insert(tenant(1, "acme-tours"), key("key-0000001"));
    assertThatThrownBy(() -> tenants.insert(tenant(2, "acme-tours"), key("key-0000001")))
        .isInstanceOf(RepeatKeyUsedException.class);
  }

  @Test
  void I_two_creations_of_one_identifier_at_once_create_one() throws Exception {
    int requests = 6;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Throwable>> outcomes = new ArrayList<>();
    for (int n = 1; n <= requests; n++) {
      int id = n;
      outcomes.add(pool.submit(() -> {
        start.await();
        try {
          tenants.insert(tenant(id, "acme-tours"), key("key-000000" + id));
          return null;
        } catch (RuntimeException e) {
          return e;
        }
      }));
    }
    start.countDown();
    List<Throwable> failures = new ArrayList<>();
    for (Future<Throwable> outcome : outcomes) {
      Throwable failure = outcome.get();
      if (failure != null) {
        failures.add(failure);
      }
    }
    pool.shutdown();
    assertThat(failures).hasSize(requests - 1).allMatch(f -> f instanceof IdentifierTakenException);
    assertThat(count("tenants")).isEqualTo(1);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }

  @Test
  void I_a_stored_tenant_that_breaks_an_invariant_is_a_failure_of_ours_and_not_a_refusal_of_the_caller() {
    // the validator cannot say that a pending tenant has no administrator; the core does
    client.getDatabase(url.getDatabase()).getCollection("tenants").insertOne(new Document("_id", "00000000-0000-4000-8000-000000000001")
        .append("identifier", "corrupt-one").append("commercialName", "Corrupt").append("tenantStatus", "PENDING")
        .append("timeZone", "America/Bogota").append("country", "CO").append("createdAt", java.util.Date.from(NOW))
        .append("updatedAt", java.util.Date.from(NOW))
        .append("firstAdministratorMembershipId", "00000000-0000-4000-8000-0000000000aa"));
    assertThatThrownBy(() -> tenants.findById("00000000-0000-4000-8000-000000000001"))
        .isInstanceOf(IllegalStateException.class);
  }
}
