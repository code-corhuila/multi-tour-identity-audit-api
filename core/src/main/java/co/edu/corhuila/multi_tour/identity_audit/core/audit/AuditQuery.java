package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * What a reader asks of the audit: the records of one tenant or of all, in a period, of one action, one page at a time. A
 * page from 1, a limit from 1 to 100, an action that some event produces and a period that does not end before it starts:
 * anything else is refused, never trimmed (Annex C). {@code from} is inclusive and {@code to} is exclusive.
 */
public record AuditQuery(String tenantId, Instant from, Instant to, String action, int page, int limit) {

  private static final Instant EARLIEST = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999999Z");
  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  public AuditQuery {
    if (page < 1 || limit < 1 || limit > 100) {
      throw new InvalidDataException("The page starts at 1 and the limit is from 1 to 100");
    }
    if (tenantId != null && !UUID_FORMAT.matcher(tenantId).matches()) {
      throw new InvalidDataException("tenantId", "must be a lowercase UUID");
    }
    if (action != null && !AuditAction.isKnown(action)) {
      throw new InvalidDataException("action", "is not an action of the audit");
    }
    inTheRange("from", from);
    inTheRange("to", to);
    if (from != null && to != null && to.isBefore(from)) {
      throw new InvalidDataException("to", "must not be before from");
    }
  }

  /** A date beyond the years 0000 to 9999 is no date of the audit, and the database could not even be asked about it. */
  private static void inTheRange(String field, Instant at) {
    if (at != null && (at.isBefore(EARLIEST) || at.isAfter(LATEST))) {
      throw new InvalidDataException(field, "must be a date between the years 0000 and 9999");
    }
  }

  /** The same question about one tenant, whatever tenant it named before. */
  public AuditQuery forTenant(String tenant) {
    return new AuditQuery(tenant, from, to, action, page, limit);
  }
}
