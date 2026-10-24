package co.edu.corhuila.multi_tour.identity_audit.core.session;

import java.time.Instant;

/**
 * From when the sessions of an account no longer count (ADR-004 D5): a token issued before {@code revokedFrom} is
 * refused. {@code tenantId} is absent for an account of the Platform Administrator.
 */
public record SessionCutoff(String accountId, String tenantId, Instant revokedFrom, Instant updatedAt) {

  /** The cutoff after an event that revokes from {@code candidate}; it never moves back. */
  public SessionCutoff moveTo(Instant candidate, Instant now) {
    return this;
  }
}
