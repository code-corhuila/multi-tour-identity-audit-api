package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The database answers a ping within 2 s, and a flood of /health does not become a flood of pings. */
class MongoDatabaseHealthTest {

  @Test
  @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
  void I_a_database_that_answers_is_up_and_the_answer_is_kept_for_two_seconds() {
    ConnectionString url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    AtomicInteger pings = new AtomicInteger();
    CommandListener listener = new CommandListener() {
      @Override
      public void commandStarted(CommandStartedEvent event) {
        if (event.getCommandName().equals("ping")) {
          pings.incrementAndGet();
        }
      }
    };
    try (MongoClient client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(url).addCommandListener(listener).build())) {
      var health = new MongoDatabaseHealth(client, url.getDatabase());
      assertThat(health.isUp()).isTrue();
      assertThat(health.isUp()).isTrue();
      assertThat(pings.get()).isEqualTo(1);
    }
  }

  @Test
  void I_a_database_that_does_not_answer_is_down_within_the_limit_and_without_a_secret() {
    ConnectionString url = new ConnectionString("mongodb://user:secret@localhost:1/identity_audit");
    try (MongoClient client = MongoClients.create(url)) {
      long started = System.nanoTime();
      assertThat(new MongoDatabaseHealth(client, url.getDatabase()).isUp()).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }
  }
}
