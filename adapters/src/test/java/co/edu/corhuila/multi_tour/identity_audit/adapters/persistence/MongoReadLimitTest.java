package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A read of the database is cut off after 5 s; a write is not, because one that timed out may have committed
 * and its repeat key makes it repeatable. The commands the driver sends are read with a listener.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoReadLimitTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final String MISSING = "00000000-0000-4000-8000-0000000000ff";

  private final List<CommandStartedEvent> sent = new CopyOnWriteArrayList<>();
  private ConnectionString url;
  private MongoClient client;

  @BeforeEach
  void aClientThatListens() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    CommandListener listener = new CommandListener() {
      @Override
      public void commandStarted(CommandStartedEvent event) {
        sent.add(event);
      }
    };
    client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(url).addCommandListener(listener).build());
    for (String collection : List.of("tenants", "outbox_events", "idempotency_keys")) {
      client.getDatabase(url.getDatabase()).getCollection(collection).deleteMany(new Document());
    }
    sent.clear();
  }

  @AfterEach
  void close() {
    client.close();
  }

  /** The commands of the repositories on the collections of the domain, by name. */
  private Map<String, List<BsonDocument>> byName() {
    return sent.stream()
        .filter(e -> e.getDatabaseName().equals(url.getDatabase()))
        .collect(java.util.stream.Collectors.groupingBy(CommandStartedEvent::getCommandName,
            java.util.stream.Collectors.mapping(CommandStartedEvent::getCommand, java.util.stream.Collectors.toList())));
  }

  @Test
  void I_every_read_carries_a_limit_of_5_seconds() {
    var tenants = new MongoTenantRepository(client, url.getDatabase());
    var outbox = new MongoOutboxRepository(client, url.getDatabase());
    tenants.findById(MISSING);
    tenants.findByIdentifier("acme-tours");
    tenants.findByRepeatKey(new RepeatKey("registerPendingTenant", "key-0000001"));
    tenants.list(null, 1, 20);
    tenants.list(TenantStatus.ACTIVE, 1, 20);
    outbox.findById(MISSING);
    outbox.due(NOW, 1, 20);
    var reads = byName();
    assertThat(reads.keySet()).contains("find", "aggregate");
    for (String name : List.of("find", "aggregate")) {
      assertThat(reads.get(name)).as(name).isNotEmpty().allSatisfy(c -> assertThat(c.containsKey("maxTimeMS") ? c.getNumber("maxTimeMS").intValue() : -1).isEqualTo(5000));
    }
  }

  @Test
  void I_a_write_carries_no_limit() {
    var tenants = new MongoTenantRepository(client, url.getDatabase());
    Tenant tenant = Tenant.registerPending(MISSING, "acme-tours", "Acme Tours", "America/Bogota", "CO", NOW);
    tenants.insert(tenant, new RepeatKey("registerPendingTenant", "key-0000001"));
    var writes = byName();
    assertThat(writes.get("insert")).isNotEmpty().allSatisfy(c -> assertThat(c.containsKey("maxTimeMS")).isFalse());
    assertThat(writes.get("commitTransaction")).isNotEmpty().allSatisfy(c -> assertThat(c.containsKey("maxTimeMS")).isFalse());
  }
}
