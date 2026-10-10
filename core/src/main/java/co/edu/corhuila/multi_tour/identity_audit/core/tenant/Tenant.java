package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * A company that operates on the platform. It is born pending; once its first Administrator exists it
 * becomes active or inactive, or failed if that Administrator cannot be created. An active tenant is
 * inactivated and an inactive one reactivated, each with who, when and why; nothing else leaves inactive,
 * and a failed tenant is final. The aggregate never changes: every change answers a new one, and a refused
 * change leaves the old one as it was.
 */
public final class Tenant {

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

  private final String tenantId;
  private final String identifier;
  private final String commercialName;
  private final String timeZone;
  private final String country;
  private final TenantStatus status;
  private final String firstAdministratorMembershipId;
  private final Instant statusChangedAt;
  private final String statusChangedBy;
  private final String statusReason;
  private final FailedStep failedStep;
  private final Instant createdAt;
  private final Instant updatedAt;

  private Tenant(
      Tenant base,
      TenantStatus status,
      String firstAdministratorMembershipId,
      Instant statusChangedAt,
      String statusChangedBy,
      String statusReason,
      FailedStep failedStep,
      Instant updatedAt) {
    this.tenantId = base.tenantId;
    this.identifier = base.identifier;
    this.commercialName = base.commercialName;
    this.timeZone = base.timeZone;
    this.country = base.country;
    this.createdAt = base.createdAt;
    this.status = status;
    this.firstAdministratorMembershipId = firstAdministratorMembershipId;
    this.statusChangedAt = statusChangedAt;
    this.statusChangedBy = statusChangedBy;
    this.statusReason = statusReason;
    this.failedStep = failedStep;
    this.updatedAt = updatedAt;
  }

  private Tenant(String tenantId, String identifier, String name, String timeZone, String country, Instant now) {
    this.tenantId = tenantId;
    this.identifier = identifier;
    this.commercialName = name;
    this.timeZone = timeZone;
    this.country = country;
    this.status = TenantStatus.PENDING;
    this.firstAdministratorMembershipId = null;
    this.statusChangedAt = null;
    this.statusChangedBy = null;
    this.statusReason = null;
    this.failedStep = null;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public static Tenant registerPending(
      String tenantId, String identifier, String commercialName, String timeZone, String country, Instant now) {
    require(tenantId != null && UUID.matcher(tenantId).matches(), "The tenant id is a lowercase UUID");
    require(
        identifier != null
            && identifier.length() >= 3
            && identifier.length() <= 40
            && IDENTIFIER.matcher(identifier).matches(),
        "The identifier is 3 to 40 lowercase letters, digits and single hyphens");
    String name = text(commercialName, 120, "The commercial name");
    require(timeZone != null && ZoneId.getAvailableZoneIds().contains(timeZone), "The time zone is a region name");
    require(country != null && COUNTRY.matcher(country).matches(), "The country is two uppercase letters");
    return new Tenant(tenantId, identifier, name, timeZone, country, now);
  }

  public Tenant completeOnboarding(String firstAdministratorMembershipId, TenantStatus initialStatus, Instant now) {
    require(
        initialStatus == TenantStatus.ACTIVE || initialStatus == TenantStatus.INACTIVE,
        "The initial status is active or inactive");
    require(
        firstAdministratorMembershipId != null && UUID.matcher(firstAdministratorMembershipId).matches(),
        "The first Administrator is a lowercase UUID");
    allow(TenantStatus.PENDING, "complete its onboarding");
    return new Tenant(this, initialStatus, firstAdministratorMembershipId, now, null, null, null, now);
  }

  public Tenant failOnboarding(FailedStep step, Instant now) {
    require(step != null, "The failed step is required");
    allow(TenantStatus.PENDING, "fail its onboarding");
    return new Tenant(this, TenantStatus.FAILED, null, now, null, null, step, now);
  }

  public Tenant inactivate(String by, String reason, Instant now) {
    String who = text(by, 100, "Who inactivates");
    String why = text(reason, 500, "The reason");
    allow(TenantStatus.ACTIVE, "be inactivated");
    return new Tenant(this, TenantStatus.INACTIVE, firstAdministratorMembershipId, now, who, why, null, now);
  }

  public Tenant reactivate(String by, String reason, Instant now) {
    String who = text(by, 100, "Who reactivates");
    String why = text(reason, 500, "The reason");
    allow(TenantStatus.INACTIVE, "be reactivated");
    return new Tenant(this, TenantStatus.ACTIVE, firstAdministratorMembershipId, now, who, why, null, now);
  }

  private void allow(TenantStatus required, String action) {
    if (status != required) {
      throw new InvalidStatusTransitionException(status, action);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** A text that is not blank, has no control character and is not longer than the limit; it is trimmed. */
  private static String text(String value, int max, String what) {
    require(value != null && !value.isBlank(), what + " is required");
    String trimmed = value.strip();
    require(trimmed.length() <= max, what + " is at most " + max + " characters");
    require(trimmed.chars().noneMatch(Character::isISOControl), what + " has no control character");
    return trimmed;
  }

  public String tenantId() { return tenantId; }
  public String identifier() { return identifier; }
  public String commercialName() { return commercialName; }
  public String timeZone() { return timeZone; }
  public String country() { return country; }
  public TenantStatus status() { return status; }
  public String firstAdministratorMembershipId() { return firstAdministratorMembershipId; }
  public Instant statusChangedAt() { return statusChangedAt; }
  public String statusChangedBy() { return statusChangedBy; }
  public String statusReason() { return statusReason; }
  public FailedStep failedStep() { return failedStep; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
