package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;
import java.util.List;

/**
 * The first Administrator could not be created: the tenant is marked failed with the step, and tells nobody.
 * It is a compensation, so it can be sent again: the same step on a failed tenant answers the tenant.
 */
public final class FailOnboarding {

  public record Command(String tenantId, FailedStep step) {

    public Command {
      Tenant.requireUuid(tenantId, "The tenant");
      if (step == null) {
        throw new IllegalArgumentException("The failed step is required");
      }
    }
  }

  private final TenantRepository tenants;
  private final Clock clock;

  public FailOnboarding(TenantRepository tenants, Clock clock) {
    this.tenants = tenants;
    this.clock = clock;
  }

  public Tenant execute(Command command) {
    try {
      return attempt(command);
    } catch (ConcurrentChangeException raced) {
      return attempt(command);
    }
  }

  private Tenant attempt(Command command) {
    Tenant current =
        tenants.findById(command.tenantId()).orElseThrow(() -> new TenantNotFoundException(command.tenantId()));
    if (current.status() == TenantStatus.FAILED && current.failedStep() == command.step()) {
      return current;
    }
    Tenant failed = current.failOnboarding(command.step(), clock.instant());
    tenants.update(current, failed, List.of());
    return failed;
  }
}
