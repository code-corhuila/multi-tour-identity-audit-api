package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Where tenants are kept. The adapter writes a creation and its repeat key in one transaction (plan D-05). */
public interface TenantRepository {

  Optional<Tenant> findById(String tenantId);

  /**
   * The tenants of one status, or (null) of all, newest first by creation and then by id, so that a tie does not
   * repeat or skip a row between pages. A page beyond the last is empty with the real total; compute the offset
   * as a long.
   */
  Page<Tenant> list(TenantStatus status, int page, int limit);

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
   * Stores {@code next}, the changed tenant, and its events together, or neither, only if the stored tenant is
   * still {@code expected}, the one the change was decided on. It compares everything a change can touch
   * (status, administrator, who, why, when and the failed step): the identifier, the name, the time zone, the
   * country and the creation time never change. Comparing the status alone would not see a tenant that
   * changed and came back.
   *
   * @throws ConcurrentChangeException if another request changed it first
   */
  void update(Tenant expected, Tenant next, List<TenantEvent> events);

  /**
   * Stores the visual identity of the tenant and the time of the change, only if the tenant exists and is
   * active, and answers whether it did. It is one write on one document, so a tenant that was inactivated
   * after it was read is not changed; the caller decides what a {@code false} means.
   */
  boolean saveVisualIdentity(String tenantId, VisualIdentity identity, Instant now);
}