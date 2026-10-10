package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The port as the real adapter must keep it: a live tenant holds its identifier and a key is stored once. */
final class InMemoryTenants implements TenantRepository {

  final Map<String, Tenant> tenants = new HashMap<>();
  final Map<RepeatKey, String> keys = new HashMap<>();
  final List<TenantEvent> events = new ArrayList<>();
  /** Runs just before the next insert or update: another request that arrives first, with real state. */
  Runnable beforeNextWrite;
  /** A real adapter may find the identifier conflict before the key one: both orders must be answered. */
  boolean identifierConflictFirst;

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
  public Optional<Tenant> findByRepeatKey(RepeatKey key) {
    return Optional.ofNullable(keys.get(key)).map(tenants::get);
  }

  private void anotherRequestArrivesFirst() {
    if (beforeNextWrite != null) {
      Runnable winner = beforeNextWrite;
      beforeNextWrite = null;
      winner.run();
    }
  }

  @Override
  public void insert(Tenant tenant, RepeatKey key) {
    anotherRequestArrivesFirst();
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

  @Override
  public void update(Tenant next, Instant expectedUpdatedAt, List<TenantEvent> caused) {
    anotherRequestArrivesFirst();
    Tenant stored = tenants.get(next.tenantId());
    if (stored == null || !stored.updatedAt().equals(expectedUpdatedAt)) {
      throw new ConcurrentChangeException(next.tenantId());
    }
    tenants.put(next.tenantId(), next);
    events.addAll(caused);
  }
}
