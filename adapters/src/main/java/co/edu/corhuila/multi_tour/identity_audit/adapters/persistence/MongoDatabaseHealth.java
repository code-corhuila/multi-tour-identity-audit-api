package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.health.DatabaseHealth;
import com.mongodb.client.MongoClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;

/**
 * A ping of the database, waited for 2 s at most, and its answer kept for 2 s: a flood of /health must not become a
 * flood of pings or of waiting threads. One caller pings; whoever arrives meanwhile gets the last answer at once
 * (down until the first ping ends). A failure says nothing but "down": the message of the driver can carry the address.
 */
public final class MongoDatabaseHealth implements DatabaseHealth {

  private static final long WAIT_SECONDS = 2;
  private static final long KEEP_NANOS = TimeUnit.SECONDS.toNanos(2);

  private final MongoClient client;
  private final String database;
  // one thread of its own, so a ping that hangs does not take a worker of the shared pool
  private final ExecutorService pinger = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "database-health");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicBoolean pinging = new AtomicBoolean();
  private volatile boolean up;
  private volatile boolean checked;
  private volatile long checkedAt;

  public MongoDatabaseHealth(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  @Override
  public boolean isUp() {
    if (checked && System.nanoTime() - checkedAt < KEEP_NANOS) {
      return up;
    }
    if (!pinging.compareAndSet(false, true)) {
      return up;
    }
    try {
      Future<?> ping = pinger.submit(() -> client.getDatabase(database).runCommand(new Document("ping", 1)));
      try {
        ping.get(WAIT_SECONDS, TimeUnit.SECONDS);
        up = true;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        ping.cancel(true);
        up = false;
      } catch (Exception failed) {
        ping.cancel(true);
        up = false;
      }
      checked = true;
      checkedAt = System.nanoTime();
      return up;
    } finally {
      pinging.set(false);
    }
  }
}
