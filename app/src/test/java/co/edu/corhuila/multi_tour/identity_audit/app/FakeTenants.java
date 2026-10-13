package co.edu.corhuila.multi_tour.identity_audit.app;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.VisualIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The port kept in memory, for the route tests that need no database: only what the routes under test use. */
final class FakeTenants implements TenantRepository {

  final Map<String, Tenant> tenants = new ConcurrentHashMap<>();

  @Override
  public Optional<Tenant> findById(String tenantId) {
    return Optional.ofNullable(tenants.get(tenantId));
  }

  @Override
  public Optional<Tenant> findByIdentifier(String identifier) {
    return tenants.values().stream()
        .filter(t -> t.identifier().equals(identifier) && t.status() != TenantStatus.FAILED)
        .findFirst();
  }

  @Override
  public boolean saveVisualIdentity(String tenantId, VisualIdentity identity, Instant now) {
    Tenant stored = tenants.get(tenantId);
    if (stored == null || stored.status() != TenantStatus.ACTIVE) {
      return false;
    }
    tenants.put(tenantId, stored.withVisualIdentity(identity, now));
    return true;
  }

  @Override
  public void update(Tenant expected, Tenant next, List<TenantEvent> events) {
    if (!expected.equals(tenants.get(next.tenantId()))) {
      throw new ConcurrentChangeException(next.tenantId());
    }
    tenants.put(next.tenantId(), next);
  }

  @Override
  public Page<Tenant> list(TenantStatus status, int page, int limit) {
    throw new UnsupportedOperationException("not used by these tests");
  }

  @Override
  public Optional<Tenant> findByRepeatKey(RepeatKey key) {
    throw new UnsupportedOperationException("not used by these tests");
  }

  @Override
  public void insert(Tenant tenant, RepeatKey key) {
    throw new UnsupportedOperationException("not used by these tests");
  }
}