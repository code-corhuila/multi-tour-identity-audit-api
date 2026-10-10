package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.CountOptions;
import java.util.concurrent.TimeUnit;

/**
 * The time a read of the database may take (Annex C): 5 s. Only reads carry it: a write that timed out may
 * have committed, and the key of a creation is what makes it repeatable.
 */
final class ReadLimit {

  private static final int SECONDS = 5;

  private ReadLimit() {}

  static <T> FindIterable<T> of(FindIterable<T> find) {
    return find.maxTime(SECONDS, TimeUnit.SECONDS);
  }

  static CountOptions count() {
    return new CountOptions().maxTime(SECONDS, TimeUnit.SECONDS);
  }
}
