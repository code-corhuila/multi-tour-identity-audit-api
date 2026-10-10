package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

/** The first Administrator could not be created: the tenant is marked failed with the step, and tells nobody. */
public final class FailOnboarding {

  public record Command(String tenantId, FailedStep step) {}

  public FailOnboarding(TenantRepository tenants, Clock clock) {}

  public Tenant execute(Command command) {
    throw new UnsupportedOperationException();
  }
}
