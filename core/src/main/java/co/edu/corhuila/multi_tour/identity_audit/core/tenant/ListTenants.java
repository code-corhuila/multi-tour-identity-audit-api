package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The Platform Administrator lists the tenants, newest first, by status or all. */
public final class ListTenants {

  /** {@code status} null is all; {@code page} from 1 and {@code limit} from 1 to 100, never trimmed. */
  public record Query(TenantStatus status, int page, int limit) {

    public Query {
      if (page < 1 || limit < 1 || limit > 100) {
        throw new IllegalArgumentException("The page starts at 1 and the limit is from 1 to 100");
      }
    }
  }

  private final TenantRepository tenants;

  public ListTenants(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Page<Tenant> execute(Query query) {
    return tenants.list(query.status(), query.page(), query.limit());
  }
}
