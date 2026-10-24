package co.edu.corhuila.multi_tour.identity_audit.core.session;

import java.time.Instant;
import java.util.Optional;

/** Where the cutoffs of the accounts and the ids of the events already applied are kept. */
public interface SessionCutoffs {

  Optional<SessionCutoff> find(String accountId);

  /**
   * Writes the event id and the cutoff in one transaction. It answers {@code false}, writing nothing, when the event id
   * was already applied. {@code expected} is the cutoff the decision was made on ({@code null} when there was none); when
   * the stored one is no longer that, it throws a {@code ConcurrentChangeException}. A {@code next} equal to
   * {@code expected} leaves the cutoff as it is and only remembers the event.
   */
  boolean record(String eventId, SessionCutoff expected, SessionCutoff next, Instant now);
}
