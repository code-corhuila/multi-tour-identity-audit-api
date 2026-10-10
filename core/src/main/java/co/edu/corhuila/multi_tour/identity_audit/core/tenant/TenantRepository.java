package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;
import java.util.Optional;

/** Where tenants are kept. The adapter writes a creation and its repeat key in one transaction (plan D-05). */
public interface TenantRepository {

  Optional<Tenant> findById(String tenantId);

  /** The tenant that holds the identifier: pending, active or inactive. A failed tenant does not hold it. */
  Optional<Tenant> findByIdentifier(String identifier);

  /** The tenant that a first request created with this key, in the state it has now. */
  Optional<Tenant> findByRepeatKey(RepeatKey key);

  /**
   * Stores a new tenant and its repeat key together, or neither. When both conflict, the repeat key wins:
   * the request that lost is a repeat of the one that won, and must be answered with its tenant. A conflict
   * leaves the lookups usable and they read committed state, so the loser can find the winner.
   *
   * @throws RepeatKeyUsedException if the key was stored first
   * @throws IdentifierTakenException if a live tenant got the identifier first
   */
  void insert(Tenant tenant, RepeatKey key);

  /**
   * Stores the changed tenant and its events together, or neither, only if the stored tenant is still in the
   * status the change was decided on.
   *
   * @throws ConcurrentChangeException if another request changed it first
   */
  void update(Tenant next, TenantStatus expectedStatus, List<TenantEvent> events);
}
