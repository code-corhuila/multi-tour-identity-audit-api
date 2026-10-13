package co.edu.corhuila.multi_tour.identity_audit.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.adapters.persistence.MongoTenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.VisualIdentity;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxRepository;
import co.edu.corhuila.multi_tour.identity_audit.adapters.persistence.MongoOutboxRepository;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * A request that loses the race twice in a row is not retried again, and the six operations that change something
 * answer 422 BUSINESS_RULE_VIOLATION, which the contract lists. The repositories here read the real database but
 * lose every write, so the second attempt of each use case fails as well.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@Import(MongoLostRaceTest.AlwaysLosing.class)
class MongoLostRaceTest extends ShellTest {

  @TestConfiguration
  static class AlwaysLosing {
    @Bean
    @Primary
    TenantRepository losingTenants(MongoClient client, ConnectionString connection) {
      MongoTenantRepository real = new MongoTenantRepository(client, connection.getDatabase());
      return new TenantRepository() {
        public Optional<Tenant> findById(String id) { return real.findById(id); }
        public Page<Tenant> list(TenantStatus status, int page, int limit) { return real.list(status, page, limit); }
        public Optional<Tenant> findByIdentifier(String identifier) { return real.findByIdentifier(identifier); }
        public Optional<Tenant> findByRepeatKey(RepeatKey key) { return real.findByRepeatKey(key); }
        public void insert(Tenant tenant, RepeatKey key) { real.insert(tenant, key); }
        public void update(Tenant expected, Tenant next, List<TenantEvent> events) { throw new ConcurrentChangeException(next.tenantId()); }
        public boolean saveVisualIdentity(String tenantId, VisualIdentity identity, Instant now) { return false; }
      };
    }

    @Bean
    @Primary
    OutboxRepository losingOutbox(MongoClient client, ConnectionString connection) {
      MongoOutboxRepository real = new MongoOutboxRepository(client, connection.getDatabase());
      return new OutboxRepository() {
        public Page<OutboxEvent> due(Instant now, int page, int limit) { return real.due(now, page, limit); }
        public Optional<OutboxEvent> findById(String id) { return real.findById(id); }
        public void update(OutboxEvent expected, OutboxEvent next) { throw new ConcurrentChangeException(next.eventId()); }
      };
    }
  }

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final String MEMBERSHIP = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String EVENT = "00000000-0000-4000-8000-0000000000e1";
  private static final String BASE = "/api/v1/identity-audit";

  @Autowired MongoClient client;
  @Autowired ConnectionString connection;
  @Value("#{new com.mongodb.ConnectionString('${MONGODB_URI}').getDatabase()}")
  String database;

  @BeforeEach
  void aCleanDatabase() {
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(database).getCollection(collection).deleteMany(new Document());
    }
  }

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  private void store(int n, String identifier, TenantStatus status) {
    Tenant tenant = Tenant.registerPending(id(n), identifier, "Tours", "America/Bogota", "CO", NOW);
    if (status != TenantStatus.PENDING) {
      tenant = tenant.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, NOW);
    }
    if (status == TenantStatus.INACTIVE) {
      tenant = tenant.inactivate("platform-admin", "unpaid", NOW);
    }
    new MongoTenantRepository(client, database).insert(tenant, new RepeatKey("registerPendingTenant", "key-%07d".formatted(n)));
  }

  private static Response post(String token, String path, Object body) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).filter(Contract.answers()).body(body).post(BASE + path);
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("onboard-tenant", 600, List.of("SERVICE"), null));
  }

  private static String admin() {
    return Tokens.valid(Tokens.admin());
  }

  private static void assertLost(Response response) {
    assertThat(response.statusCode()).as("%s", response.asString()).isEqualTo(422);
    assertThat(response.jsonPath().getString("error")).isEqualTo("BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_the_four_changes_of_a_tenant_that_lose_the_race_twice_answer_422_business_rule_violation() {
    store(1, "pending-tours", TenantStatus.PENDING);
    store(2, "active-tours", TenantStatus.ACTIVE);
    store(3, "off-tours", TenantStatus.INACTIVE);
    store(4, "other-tours", TenantStatus.PENDING);
    assertLost(post(service(), "/internal/tenants/" + id(1) + "/complete-onboarding",
        Map.of("firstAdministratorMembershipId", MEMBERSHIP, "initialStatus", "ACTIVE")));
    assertLost(post(service(), "/internal/tenants/" + id(4) + "/fail-onboarding", Map.of("failedStep", "CREATE_ACCOUNT")));
    assertLost(post(admin(), "/tenants/" + id(2) + "/inactivate", Map.of("reason", "unpaid")));
    assertLost(post(admin(), "/tenants/" + id(3) + "/reactivate", Map.of("reason", "paid")));
  }

  @Test
  void C_the_two_changes_of_an_event_that_lose_the_race_twice_answer_422_business_rule_violation() {
    client.getDatabase(database).getCollection("outbox_events").insertOne(
        new Document("_id", EVENT).append("eventType", "TenantCreated").append("eventVersion", 1).append("aggregateId", id(1))
            .append("payload", new Document("n", 1)).append("correlationId", "corr-1").append("occurredAt", Date.from(NOW))
            .append("status", "PENDING").append("attempts", 0).append("nextAttemptAt", Date.from(NOW)));
    assertLost(post(service(), "/internal/outbox/" + EVENT + "/published", Map.of()));
    assertLost(post(service(), "/internal/outbox/" + EVENT + "/attempts", Map.of("error", "down")));
  }
}
