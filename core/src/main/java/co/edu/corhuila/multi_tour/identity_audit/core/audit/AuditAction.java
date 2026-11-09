package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The action of an audit record is the type of the event it came from, in SCREAMING_SNAKE_CASE, until {@code 02-domain}
 * closes the list (DP-IDA-M-04). {@link #KNOWN} is the list of the events that exist today: a filter by any other action is
 * refused, while recording accepts any event type, so a new publisher needs no change here to be recorded.
 */
public final class AuditAction {

  public static final Set<String> KNOWN = Set.of(
      "ACCOUNT_SESSIONS_REVOKED", "ACCOUNT_BLOCKED", "SPECIAL_PERMISSION_GRANTED", "SPECIAL_PERMISSION_REVOKED",
      "OPTIONAL_ROLE_ENABLED", "OPTIONAL_ROLE_DISABLED", "TENANT_CREATED", "TENANT_INACTIVATED", "TENANT_REACTIVATED",
      "AUDIT_ACCESS_ENABLED", "AUDIT_ACCESS_DISABLED",
      // the events of other services that the audit records (spec 008)
      "ACCOUNT_EMAIL_VERIFIED", "RESERVATION_CREATED", "RESERVATION_MODIFIED",
      "CAPACITY_ALLOCATED", "CAPACITY_RELEASED", "PAYMENT_EVIDENCE_REGISTERED",
      "PAYMENT_VALIDATED", "PAYMENT_REJECTED", "RESERVATION_CONFIRMED",
      "RESERVATION_CANCELLED", "REFUND_AUTHORIZED", "CLIENT_NOTIFIED",
      "CUSTOMER_REGISTERED", "DISCOUNT_RULE_CHANGED", "SENSITIVE_DATA_ACCESSED",
      // the exceptional access of the Platform Administrator to a tenant (spec 009)
      "PLATFORM_EXCEPTIONAL_ACCESS_OPENED", "PLATFORM_EXCEPTIONAL_ACCESS_CLOSED");

  private static final int MAX_LENGTH = 64;
  private static final Pattern EVENT_TYPE = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
  private static final Pattern WORD_START = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

  private AuditAction() {}

  /** {@code SpecialPermissionGranted} becomes {@code SPECIAL_PERMISSION_GRANTED}. */
  public static String of(String eventType) {
    if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
      throw new InvalidDataException("eventType", "must be a name such as SpecialPermissionGranted");
    }
    String action = WORD_START.matcher(eventType).replaceAll("_").toUpperCase(Locale.ROOT);
    // the underscores can make it longer than the type, and a record refuses an action over 64
    if (action.length() > MAX_LENGTH) {
      throw new InvalidDataException("eventType", "is too long to make an action of at most " + MAX_LENGTH + " characters");
    }
    return action;
  }

  public static boolean isKnown(String action) {
    return action != null && KNOWN.contains(action);
  }
}
