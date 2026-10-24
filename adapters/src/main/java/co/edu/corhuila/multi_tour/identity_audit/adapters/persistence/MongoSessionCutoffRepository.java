package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoff;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoffs;
import com.mongodb.client.MongoClient;
import java.time.Instant;
import java.util.Optional;

/** The cutoffs in {@code account_session_cutoffs} and the event ids in {@code processed_events}. */
public final class MongoSessionCutoffRepository implements SessionCutoffs {

  public MongoSessionCutoffRepository(MongoClient client, String database) {}

  @Override
  public Optional<SessionCutoff> find(String accountId) {
    return Optional.empty();
  }

  @Override
  public boolean record(String eventId, SessionCutoff expected, SessionCutoff next, Instant now) {
    return false;
  }
}
