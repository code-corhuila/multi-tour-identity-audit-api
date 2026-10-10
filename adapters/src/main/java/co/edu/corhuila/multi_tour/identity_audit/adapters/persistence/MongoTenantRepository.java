package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.client.MongoClient;
import java.util.List;
import java.util.Optional;

/** The tenants in the database {@code identity_audit}, which {@code multi-tour-identity-audit-db} builds. */
public final class MongoTenantRepository implements TenantRepository {

  public MongoTenantRepository(MongoClient client, String database) {}

  @Override
  public Optional<Tenant> findById(String tenantId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page<Tenant> list(TenantStatus status, int page, int limit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Tenant> findByIdentifier(String identifier) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Tenant> findByRepeatKey(RepeatKey key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insert(Tenant tenant, RepeatKey key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void update(Tenant expected, Tenant next, List<TenantEvent> events) {
    throw new UnsupportedOperationException();
  }
}
