package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The port as the real adapter must keep it: a live tenant holds its identifier and a key is stored once. */
final class InMemoryTenants implements TenantRepository {

  final Map<String, Tenant> tenants = new HashMap<>();
  final Map<RepeatKey, String> keys = new HashMap<>();
  /** Runs just before the next insert: another request that arrives first, with real state behind it. */
  Runnable beforeNextInsert;
  /** A real adapter may find the identifier conflict before the key one: both orders must be answered. */
  boolean identifierConflictFirst;

  @Override
  public Optional<Tenant> findByIdentifier(String identifier) {
    return tenants.values().stream()
        .filter(t -> t.identifier().equals(identifier) && t.status() != TenantStatus.FAILED)
        .findFirst();
  }

  @Override
  public Optional<Tenant> findByRepeatKey(RepeatKey key) {
    return Optional.ofNullable(keys.get(key)).map(tenants::get);
  }

  @Override
  public void insert(Tenant tenant, RepeatKey key) {
    if (beforeNextInsert != null) {
      Runnable winner = beforeNextInsert;
      beforeNextInsert = null;
      winner.run();
    }
    boolean keyTaken = keys.containsKey(key);
    boolean identifierTaken = findByIdentifier(tenant.identifier()).isPresent();
    if (identifierTaken && (identifierConflictFirst || !keyTaken)) {
      throw new IdentifierTakenException(tenant.identifier());
    }
    if (keyTaken) {
      throw new RepeatKeyUsedException(key);
    }
    tenants.put(tenant.tenantId(), tenant);
    keys.put(key, tenant.tenantId());
  }
}
