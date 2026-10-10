package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;
import java.util.Optional;

/** Step 1 of the saga onboard-tenant: the tenant is created pending. */
public final class RegisterPendingTenant {

  public static final String OPERATION = "registerPendingTenant";

  public record Command(
      String identifier, String commercialName, String timeZone, String country, String idempotencyKey) {}

  /** The tenant, and whether this call created it or answered the one a first call created. */
  public record Result(Tenant tenant, boolean created) {}

  private final TenantRepository tenants;
  private final IdGenerator ids;
  private final Clock clock;

  public RegisterPendingTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    this.tenants = tenants;
    this.ids = ids;
    this.clock = clock;
  }

  public Result execute(Command command) {
    RepeatKey key = new RepeatKey(OPERATION, command.idempotencyKey());
    // a repeat answers the first tenant as it is now, without looking at the body (Annex C)
    Optional<Tenant> first = tenants.findByRepeatKey(key);
    if (first.isPresent()) {
      return new Result(first.get(), false);
    }
    Tenant tenant =
        Tenant.registerPending(
            ids.newId(), command.identifier(), command.commercialName(), command.timeZone(), command.country(),
            clock.instant());
    // not redundant with the insert: it refuses the common case without spending the key
    if (tenants.findByIdentifier(tenant.identifier()).isPresent()) {
      throw new IdentifierTakenException(tenant.identifier());
    }
    try {
      tenants.insert(tenant, key);
      return new Result(tenant, true);
    } catch (RepeatKeyUsedException | IdentifierTakenException lost) {
      // the request that won may be the first send of this same one: the key decides
      Optional<Tenant> winner = tenants.findByRepeatKey(key);
      if (winner.isPresent()) {
        return new Result(winner.get(), false);
      }
      throw lost;
    }
  }
}
