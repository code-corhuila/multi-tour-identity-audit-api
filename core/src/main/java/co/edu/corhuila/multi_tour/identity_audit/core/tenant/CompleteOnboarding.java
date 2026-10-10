package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** Step 3 of the saga: the first Administrator exists, so the tenant takes the status chosen at registration. */
public final class CompleteOnboarding {

  public record Command(
      String tenantId, String firstAdministratorMembershipId, TenantStatus initialStatus, String actor,
      String correlationId) {}

  public CompleteOnboarding(TenantRepository tenants, IdGenerator ids, Clock clock) {}

  public Tenant execute(Command command) {
    throw new UnsupportedOperationException();
  }
}
