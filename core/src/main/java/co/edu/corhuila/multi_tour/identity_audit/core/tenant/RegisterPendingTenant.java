package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Clock;

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
    throw new UnsupportedOperationException();
  }
}
