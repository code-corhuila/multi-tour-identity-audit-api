package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;
import java.util.List;

/**
 * Step 3 of the saga: the first Administrator exists, so the tenant takes the status chosen at registration.
 * A step may be sent again after a crash: the same data on a tenant that already has it answers the tenant.
 */
public final class CompleteOnboarding {

  public record Command(
      String tenantId, String firstAdministratorMembershipId, TenantStatus initialStatus, String actor,
      String correlationId) {

    public Command {
      Tenant.requireUuid(tenantId, "The tenant");
      Tenant.requireUuid(firstAdministratorMembershipId, "The first Administrator");
      TenantEvent.requireWho(actor, correlationId);
      if (initialStatus != TenantStatus.ACTIVE && initialStatus != TenantStatus.INACTIVE) {
        throw new IllegalArgumentException("The initial status is active or inactive");
      }
    }
  }

  private final TenantRepository tenants;
  private final IdGenerator ids;
  private final Clock clock;

  public CompleteOnboarding(TenantRepository tenants, IdGenerator ids, Clock clock) {
    this.tenants = tenants;
    this.ids = ids;
    this.clock = clock;
  }

  public Tenant execute(Command command) {
    try {
      return attempt(command);
    } catch (ConcurrentChangeException raced) {
      // another request changed it while this one decided: decide again on what it left
      return attempt(command);
    }
  }

  private Tenant attempt(Command command) {
    Tenant current =
        tenants.findById(command.tenantId()).orElseThrow(() -> new TenantNotFoundException(command.tenantId()));
    // the administrator is set once and never changes, so it tells this step was already done, whatever came after
    if (command.firstAdministratorMembershipId().equals(current.firstAdministratorMembershipId())) {
      return current;
    }
    Tenant done =
        current.completeOnboarding(command.firstAdministratorMembershipId(), command.initialStatus(), clock.instant());
    TenantEvent created =
        new TenantEvent(
            ids.newId(), TenantEventType.TENANT_CREATED, done, command.actor(), command.correlationId(),
            done.updatedAt());
    tenants.update(current, done, List.of(created));
    return done;
  }
}
