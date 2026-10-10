package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.Optional;

/** Where tenants are kept. The adapter writes a creation and its repeat key in one transaction (plan D-05). */
public interface TenantRepository {

  /** The tenant that holds the identifier: pending, active or inactive. A failed tenant does not hold it. */
  Optional<Tenant> findByIdentifier(String identifier);

  /** The tenant that a first request created with this key, in the state it has now. */
  Optional<Tenant> findByRepeatKey(RepeatKey key);

  /**
   * Stores a new tenant and its repeat key together, or neither. When both conflict, the repeat key wins:
   * the request that lost is a repeat of the one that won, and must be answered with its tenant.
   *
   * @throws RepeatKeyUsedException if the key was stored first
   * @throws IdentifierTakenException if a live tenant got the identifier first
   */
  void insert(Tenant tenant, RepeatKey key);
}
