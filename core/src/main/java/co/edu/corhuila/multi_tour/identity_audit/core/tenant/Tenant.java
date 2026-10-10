package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;

/** A company that operates on the platform. */
public final class Tenant {

  public static Tenant registerPending(
      String tenantId, String identifier, String commercialName, String timeZone, String country, Instant now) {
    throw new UnsupportedOperationException();
  }

  public Tenant completeOnboarding(String firstAdministratorMembershipId, TenantStatus initialStatus, Instant now) {
    throw new UnsupportedOperationException();
  }

  public Tenant failOnboarding(FailedStep step, Instant now) {
    throw new UnsupportedOperationException();
  }

  public Tenant inactivate(String by, String reason, Instant now) {
    throw new UnsupportedOperationException();
  }

  public Tenant reactivate(String by, String reason, Instant now) {
    throw new UnsupportedOperationException();
  }

  public String tenantId() { return null; }
  public String identifier() { return null; }
  public String commercialName() { return null; }
  public String timeZone() { return null; }
  public String country() { return null; }
  public TenantStatus status() { return null; }
  public String firstAdministratorMembershipId() { return null; }
  public Instant statusChangedAt() { return null; }
  public String statusChangedBy() { return null; }
  public String statusReason() { return null; }
  public FailedStep failedStep() { return null; }
  public Instant createdAt() { return null; }
  public Instant updatedAt() { return null; }
}
