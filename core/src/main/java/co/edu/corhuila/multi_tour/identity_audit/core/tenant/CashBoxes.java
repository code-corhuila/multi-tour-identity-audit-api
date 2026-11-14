package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** Port: what this service needs to know of the cash boxes of a tenant, which another service owns (ADR-004 D4, level 2 read). */
public interface CashBoxes {

  /**
   * Whether the tenant has any cash box that is open or pending closing. It reads at the moment it is asked and keeps nothing.
   *
   * @throws CashBoxesUnavailableException if the state cannot be read: the caller must not guess
   */
  boolean anyOpenOrPendingClosing(String tenantId);
}
