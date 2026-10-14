package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.health.DatabaseHealth;
import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness with a look at the database. A database that does not answer is said to be down, not a 503: the
 * orchestrator would restart a service that cannot fix the database, and the restart loop would hide the failure.
 */
@RestController
class HealthController {

  public record Dependencies(String database) {}

  public record Health(String status, Instant timestamp, Dependencies dependencies) {}

  private final DatabaseHealth database;
  private final Clock clock;

  HealthController(DatabaseHealth database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  @GetMapping("/health")
  Health health() {
    boolean up;
    try {
      up = database.isUp();
    } catch (RuntimeException failed) {
      up = false;
    }
    return new Health("ok", clock.instant(), new Dependencies(up ? "ok" : "down"));
  }
}
