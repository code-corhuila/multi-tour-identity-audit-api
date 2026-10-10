package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The Platform Administrator lists the tenants, newest first, by status or all. */
public final class ListTenants {

  /** {@code status} null is all; {@code page} from 1 and {@code limit} from 1 to 100. */
  public record Query(TenantStatus status, int page, int limit) {}

  public ListTenants(TenantRepository tenants) {}

  public Page<Tenant> execute(Query query) {
    throw new UnsupportedOperationException();
  }
}
