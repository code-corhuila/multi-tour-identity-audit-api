package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * A company that operates on the platform. It is born pending; once its first Administrator exists it
 * becomes active or inactive, or failed if that Administrator cannot be created. An active tenant is
 * inactivated and an inactive one reactivated, each with who, when and why; nothing else leaves inactive,
 * and a failed tenant is final. Every change answers a new tenant; a refused one leaves the old as it was.
 * One that is built, new or read from the database, gets the same checks. A bad argument is an
 * {@link IllegalArgumentException}, checked before the status, so the caller answers 400 before 422.
 */
public record Tenant(
    String tenantId,
    String identifier,
    String commercialName,
    String timeZone,
    String country,
    Instant createdAt,
    TenantStatus status,
    String firstAdministratorMembershipId,
    Instant statusChangedAt,
    String statusChangedBy,
    String statusReason,
    FailedStep failedStep,
    Instant updatedAt) {

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Pattern UUID_FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
  private static final Pattern REGION =
      Pattern.compile("(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+|UTC");

  public Tenant {
    require(tenantId != null && UUID_FORMAT.matcher(tenantId).matches(), "The tenant id is a lowercase UUID");
    require(
        isIdentifier(identifier), "The identifier is 3 to 40 lowercase letters, digits and single hyphens");
    commercialName = text(commercialName, 120, "The commercial name");
    require(
        timeZone != null && REGION.matcher(timeZone).matches() && ZoneId.getAvailableZoneIds().contains(timeZone),
        "The time zone is a region name such as America/Bogota");
    require(country != null && COUNTRY.matcher(country).matches(), "The country is two uppercase letters");
    require(status != null, "The status is required");
    require(
        firstAdministratorMembershipId == null || UUID_FORMAT.matcher(firstAdministratorMembershipId).matches(),
        "The first Administrator is a lowercase UUID");
    require(
        (status == TenantStatus.ACTIVE || status == TenantStatus.INACTIVE)
            == (firstAdministratorMembershipId != null),
        "Only a tenant that finished its onboarding has a first Administrator");
    require((status == TenantStatus.FAILED) == (failedStep != null), "Only a failed tenant has a failed step");
    statusChangedBy = optionalText(statusChangedBy, 100, "Who made the change");
    statusReason = optionalText(statusReason, 500, "The reason");
    require((statusChangedBy == null) == (statusReason == null), "Who and why come together");
    require(
        (status != TenantStatus.PENDING && status != TenantStatus.FAILED) || statusChangedBy == null,
        "A pending or failed tenant has no one who changed it");
    createdAt = moment(createdAt);
    updatedAt = moment(updatedAt);
    statusChangedAt = statusChangedAt == null ? null : moment(statusChangedAt);
  }

  public static Tenant registerPending(
      String tenantId, String identifier, String commercialName, String timeZone, String country, Instant now) {
    return new Tenant(
        tenantId, identifier, commercialName, timeZone, country, now, TenantStatus.PENDING, null, null, null, null,
        null, now);
  }

  public Tenant completeOnboarding(String firstAdministratorMembershipId, TenantStatus initialStatus, Instant now) {
    require(
        initialStatus == TenantStatus.ACTIVE || initialStatus == TenantStatus.INACTIVE,
        "The initial status is active or inactive");
    require(
        firstAdministratorMembershipId != null && UUID_FORMAT.matcher(firstAdministratorMembershipId).matches(),
        "The first Administrator is a lowercase UUID");
    Instant at = moment(now);
    allow(TenantStatus.PENDING, "complete its onboarding");
    return next(initialStatus, firstAdministratorMembershipId, null, null, null, at);
  }

  public Tenant failOnboarding(FailedStep step, Instant now) {
    require(step != null, "The failed step is required");
    Instant at = moment(now);
    allow(TenantStatus.PENDING, "fail its onboarding");
    return next(TenantStatus.FAILED, null, null, null, step, at);
  }

  public Tenant inactivate(String by, String reason, Instant now) {
    return change(TenantStatus.ACTIVE, TenantStatus.INACTIVE, "be inactivated", by, reason, now);
  }

  public Tenant reactivate(String by, String reason, Instant now) {
    return change(TenantStatus.INACTIVE, TenantStatus.ACTIVE, "be reactivated", by, reason, now);
  }

  /** The only two changes that record who and why: one status to its opposite. */
  private Tenant change(
      TenantStatus from, TenantStatus to, String action, String by, String reason, Instant now) {
    String who = text(by, 100, "Who makes the change");
    String why = text(reason, 500, "The reason");
    Instant at = moment(now);
    allow(from, action);
    return next(to, firstAdministratorMembershipId, who, why, null, at);
  }

  private Tenant next(
      TenantStatus to, String administrator, String by, String reason, FailedStep step, Instant now) {
    return new Tenant(
        tenantId, identifier, commercialName, timeZone, country, createdAt, to, administrator, now, by, reason,
        step, now);
  }

  /** Only what identifies the tenant: the free text of a reason or a name does not reach a log. */
  @Override
  public String toString() {
    return "Tenant[" + tenantId + ", " + identifier + ", " + status + "]";
  }

  private void allow(TenantStatus required, String action) {
    if (status != required) {
      throw new InvalidStatusTransitionException(status, action);
    }
  }

  /** Whether a text is written like an identifier: 3 to 40 lowercase letters, digits and single hyphens. */
  static boolean isIdentifier(String value) {
    return value != null && value.length() >= 3 && value.length() <= 40 && IDENTIFIER.matcher(value).matches();
  }

  /** A lowercase UUID, as the first Administrator and the tenant are identified. */
  static void requireUuid(String value, String what) {
    require(value != null && UUID_FORMAT.matcher(value).matches(), what + " is a lowercase UUID");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** The time of a change, to the millisecond that the database keeps. */
  private static Instant moment(Instant time) {
    require(time != null, "The time is required");
    return time.truncatedTo(ChronoUnit.MILLIS);
  }

  private static String optionalText(String value, int max, String what) {
    return value == null ? null : text(value, max, what);
  }

  /** A text that is not blank, has no control character and is not longer than the limit; it is trimmed. */
  static String text(String value, int max, String what) {
    require(value != null && !value.isBlank(), what + " is required");
    String trimmed = value.strip();
    require(trimmed.length() <= max, what + " is at most " + max + " characters");
    require(trimmed.chars().noneMatch(Character::isISOControl), what + " has no control character");
    return trimmed;
  }
}
