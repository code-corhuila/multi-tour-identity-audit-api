package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The database the integration tests run against is the one that {@code multi-tour-identity-audit-db} builds
 * (norm 5.2.1: no schema is made in this repository, and a test against a shape the real database never has
 * proves nothing). They run where {@code TEST_DATABASE_URL} points to it; {@code scripts/prepare-test-db.sh}
 * makes it, and the CI fails if these tests were skipped.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoDatabaseTest {

  private static final ConnectionString URL = new ConnectionString(System.getenv("TEST_DATABASE_URL"));

  @Test
  void I_the_database_is_the_one_the_db_repository_builds() {
    try (MongoClient client = MongoClients.create(URL)) {
      MongoDatabase database = client.getDatabase(URL.getDatabase());
      Set<String> collections = new TreeSet<>(database.listCollectionNames().into(new java.util.ArrayList<>()));
      assertThat(collections).contains("tenants", "outbox_events", "idempotency_keys");
      for (String name : List.of("tenants", "outbox_events", "idempotency_keys")) {
        Document options =
            database.listCollections().filter(new Document("name", name)).first().get("options", Document.class);
        assertThat(options.get("validator", Document.class)).as(name).containsKey("$jsonSchema");
        assertThat(options.getString("validationLevel")).as(name).isEqualTo("strict");
        assertThat(options.getString("validationAction")).as(name).isEqualTo("error");
      }
    }
  }

  @Test
  void I_the_roles_of_the_db_repository_exist() {
    try (MongoClient client = MongoClients.create(URL)) {
      Document roles = client.getDatabase(URL.getDatabase()).runCommand(new Document("rolesInfo", 1));
      List<String> names = roles.getList("roles", Document.class).stream().map(r -> r.getString("role")).toList();
      assertThat(names).contains("identity_audit_reader", "identity_audit_writer");
    }
  }
}
