package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import java.util.Map;

/**
 * Who writes each type of event and what it is about (spec 008 of the audit, HU-IDA-002 AC1): the service that wrote it is the actor when
 * no person is known, and the type says what kind of record the aggregate is. A type nobody listed is read as before: an event of the
 * security service about an account, so a new publisher is not refused.
 */
final class EventOrigin {

  static final String SECURITY_SERVICE = "multi-tour-security";
  static final String RESERVATION_SERVICE = "multi-tour-commercial-reservation";

  /** The service that wrote the event and the prefix of the record it is about. */
  record Origin(String service, String recordPrefix) {}

  private static final Origin ACCOUNT = new Origin(SECURITY_SERVICE, "accounts/");
  private static final Origin TENANT = new Origin(SECURITY_SERVICE, "tenants/");
  private static final Origin RESERVATION = new Origin(RESERVATION_SERVICE, "reservations/");
  private static final Origin CUSTOMER = new Origin(RESERVATION_SERVICE, "customers/");
  private static final Origin DISCOUNT_RULE = new Origin(RESERVATION_SERVICE, "discount-rules/");
  private static final Origin SUPPORT_ACCESS = new Origin(SECURITY_SERVICE, "support-accesses/");

  private static final Map<String, Origin> BY_TYPE = Map.ofEntries(
      Map.entry("OptionalRoleEnabled", TENANT),
      Map.entry("OptionalRoleDisabled", TENANT),
      Map.entry("ReservationCreated", RESERVATION),
      Map.entry("ReservationModified", RESERVATION),
      Map.entry("CapacityAllocated", RESERVATION),
      Map.entry("CapacityReleased", RESERVATION),
      Map.entry("PaymentEvidenceRegistered", RESERVATION),
      Map.entry("PaymentValidated", RESERVATION),
      Map.entry("PaymentRejected", RESERVATION),
      Map.entry("ReservationConfirmed", RESERVATION),
      Map.entry("ReservationCancelled", RESERVATION),
      Map.entry("RefundAuthorized", RESERVATION),
      Map.entry("ClientNotified", RESERVATION),
      Map.entry("CustomerRegistered", CUSTOMER),
      Map.entry("SensitiveDataAccessed", CUSTOMER),
      Map.entry("DiscountRuleChanged", DISCOUNT_RULE),
      Map.entry("PlatformExceptionalAccessOpened", SUPPORT_ACCESS),
      Map.entry("PlatformExceptionalAccessClosed", SUPPORT_ACCESS));

  private EventOrigin() {}

  static Origin of(String eventType) {
    return BY_TYPE.getOrDefault(eventType, ACCOUNT);
  }
}
