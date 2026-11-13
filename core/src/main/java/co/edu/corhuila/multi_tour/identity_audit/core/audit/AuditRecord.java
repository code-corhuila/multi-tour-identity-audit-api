package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The evidence of one audited action (PDR section 17, INV-AUD-001). It is never changed or deleted; a correction is a new
 * record. {@code eventId} is the event it was made from, so a repeat delivery is recognised; {@code tenantId} is absent for
 * an action of the platform. The limits are the ones the validator of the database holds, so a record the core accepts is
 * one the store accepts. The two values are kept as they come, without a copy: the code that builds a record does not
 * touch them afterwards, and a copy would refuse the null a payload may hold.
 */
public record AuditRecord(
    String auditRecordId, String eventId, String tenantId, String actorId, String action, String affectedRecordId,
    String reason, Map<String, Object> previousValue, Map<String, Object> newValue, String channelOrModule,
    String functionalProcessReference, Instant recordedAt) {

  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern ACTION_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public AuditRecord {
    requireUuid("auditRecordId", auditRecordId);
    requireUuid("eventId", eventId);
    if (tenantId != null) {
      requireUuid("tenantId", tenantId);
    }
    requireText("actorId", actorId, 100);
    if (action == null || !ACTION_FORMAT.matcher(action).matches()) {
      throw new InvalidDataException("action", "must be an action in SCREAMING_SNAKE_CASE of at most 64 characters");
    }
    requireText("affectedRecordId", affectedRecordId, 200);
    requireOptionalText("reason", reason, 500);
    requireOptionalText("channelOrModule", channelOrModule, 64);
    requireOptionalText("functionalProcessReference", functionalProcessReference, 100);
    if (recordedAt == null) {
      throw new InvalidDataException("recordedAt", "is required");
    }
  }

  private static void requireUuid(String field, String value) {
    if (value == null || !UUID_FORMAT.matcher(value).matches()) {
      throw new InvalidDataException(field, "must be a lowercase UUID");
    }
  }

  private static void requireText(String field, String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new InvalidDataException(field, "is required and at most " + max + " characters");
    }
  }

  private static void requireOptionalText(String field, String value, int max) {
    if (value != null && (value.isBlank() || value.length() > max)) {
      throw new InvalidDataException(field, "is at most " + max + " characters");
    }
  }
}
