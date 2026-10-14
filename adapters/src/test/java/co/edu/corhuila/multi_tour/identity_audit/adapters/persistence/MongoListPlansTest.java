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
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The lists of the service are read in pages in a fixed order, and the database must serve that order from an
 * index, with no sort in memory (rule RN-IDA-DB-08). The queries are the ones the repositories really send: they
 * are caught with a listener and their plan is asked for.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoListPlansTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");
  private static final Pattern SORT_STAGE = Pattern.compile("\"stage\"\\s*:\\s*\"SORT\"");
  private static final Pattern INDEX = Pattern.compile("\"indexName\"\\s*:\\s*\"([^\"]+)\"");

  private final List<CommandStartedEvent> sent = new CopyOnWriteArrayList<>();
  private ConnectionString url;
  private MongoClient client;

  @BeforeEach
  void aDatabaseWithSomeRows() {
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
    var tenants = new MongoTenantRepository(client, url.getDatabase());
    for (int n = 1; n <= 12; n++) {
      Tenant tenant = Tenant.registerPending(
          "00000000-0000-4000-8000-%012d".formatted(n), "tenant-" + n, "Tenant " + n, "America/Bogota", "CO", NOW.minusSeconds(n));
      tenants.insert(tenant, new RepeatKey("registerPendingTenant", "key-%07d".formatted(n)));
      client.getDatabase(url.getDatabase()).getCollection("outbox_events").insertOne(
          new Document("_id", "00000000-0000-4000-8000-%012d".formatted(n)).append("eventType", "TenantCreated")
              .append("eventVersion", 1).append("aggregateId", tenant.tenantId()).append("payload", new Document("n", n))
              .append("correlationId", "corr-1").append("occurredAt", Date.from(NOW.minusSeconds(n))).append("status", "PENDING")
              .append("attempts", 0).append("nextAttemptAt", Date.from(NOW.minusSeconds(n))));
    }
    sent.clear();
  }

  @AfterEach
  void close() {
    client.close();
  }

  /** The plan of the last {@code find} that the repositories sent on a collection. */
  private String planOf(String collection) {
    BsonDocument find = sent.stream().filter(e -> e.getCommandName().equals("find") && e.getCommand().getString("find").getValue().equals(collection))
        .reduce((first, second) -> second).orElseThrow().getCommand();
    Document command = new Document();
    for (String key : List.of("find", "filter", "sort", "skip", "limit")) {
      if (find.containsKey(key)) {
        command.append(key, Document.parse(new BsonDocument(key, find.get(key)).toJson()).get(key));
      }
    }
    Document explained = client.getDatabase(url.getDatabase()).runCommand(new Document("explain", command).append("verbosity", "queryPlanner"));
    // only the plan that wins: the rejected ones are rejected exactly because they sort in memory
    return explained.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
  }

  private static void assertServedByIndex(String plan, String index) {
    assertThat(SORT_STAGE.matcher(plan).find()).as("the plan sorts in memory: %s", plan).isFalse();
    Matcher used = INDEX.matcher(plan);
    assertThat(used.find()).as("the plan uses no index: %s", plan).isTrue();
    assertThat(used.group(1)).isEqualTo(index);
  }

  @Test
  void I_the_list_of_tenants_with_no_status_is_served_by_an_index_with_no_sort_stage() {
    new MongoTenantRepository(client, url.getDatabase()).list(null, 1, 5);
    assertServedByIndex(planOf("tenants"), "idx_tenants_createdat_id");
  }

  @Test
  void I_the_list_of_tenants_by_status_is_served_by_an_index_with_no_sort_stage() {
    new MongoTenantRepository(client, url.getDatabase()).list(TenantStatus.PENDING, 1, 5);
    assertServedByIndex(planOf("tenants"), "idx_tenants_status_createdat_id");
  }

  @Test
  void I_the_events_due_are_served_by_an_index_with_no_sort_stage() {
    new MongoOutboxRepository(client, url.getDatabase()).due(NOW, 1, 5);
    assertServedByIndex(planOf("outbox_events"), "idx_outbox_events_due");
  }
}
