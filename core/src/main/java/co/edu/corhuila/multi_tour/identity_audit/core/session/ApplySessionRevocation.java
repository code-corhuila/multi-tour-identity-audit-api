package co.edu.corhuila.multi_tour.identity_audit.core.session;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/** An event {@code AccountSessionsRevoked}: the account keeps a cutoff and a token issued before it is refused (ADR-004 D5). */
public final class ApplySessionRevocation {

  public static final String EVENT_TYPE = "AccountSessionsRevoked";
  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** {@code APPLIED} when the cutoff was set or moved; {@code UNCHANGED} for a repeat of the event id or an event older than the cutoff. */
  public enum Outcome { APPLIED, UNCHANGED }

  public record Command(String eventId, String eventType, String tenantId, String aggregateId, String accountId, String revokedFrom) {

    public Command {
      if (!EVENT_TYPE.equals(eventType)) {
        throw new InvalidDataException("eventType", "must be " + EVENT_TYPE);
      }
      require("eventId", eventId);
      require("tenantId", tenantId);
      require("accountId", accountId);
      if (!accountId.equals(aggregateId)) {
        throw new InvalidDataException("aggregateId", "must be the account of the payload");
      }
      if (revokedFrom == null) {
        throw new InvalidDataException("revokedFrom", "is required");
      }
    }

    Instant revokedFromInstant() {
      try {
        return Instant.parse(revokedFrom);
      } catch (DateTimeParseException notADate) {
        throw new InvalidDataException("revokedFrom", "must be an RFC 3339 date and time");
      }
    }

    private static void require(String field, String value) {
      if (value == null || !UUID_FORMAT.matcher(value).matches()) {
        throw new InvalidDataException(field, "must be a lowercase UUID");
      }
    }
  }

  private final SessionCutoffs cutoffs;
  private final Clock clock;

  public ApplySessionRevocation(SessionCutoffs cutoffs, Clock clock) {
    this.cutoffs = cutoffs;
    this.clock = clock;
  }

  /** A race decides again once, as the other changes of the service do. */
  public Outcome execute(Command command) {
    Instant from = command.revokedFromInstant();
    try {
      return attempt(command, from);
    } catch (ConcurrentChangeException raced) {
      return attempt(command, from);
    }
  }

  private Outcome attempt(Command command, Instant from) {
    Instant now = clock.instant();
    Optional<SessionCutoff> current = cutoffs.find(command.accountId());
    if (current.isPresent() && current.get().tenantId() != null && !current.get().tenantId().equals(command.tenantId())) {
      throw new InvalidDataException("tenantId", "is not the tenant of the account");
    }
    SessionCutoff next = current
        .map(kept -> kept.moveTo(from, now))
        .orElseGet(() -> new SessionCutoff(command.accountId(), command.tenantId(), from, now));
    boolean first = cutoffs.record(command.eventId(), current.orElse(null), next, now);
    return first && !next.equals(current.orElse(null)) ? Outcome.APPLIED : Outcome.UNCHANGED;
  }
}
