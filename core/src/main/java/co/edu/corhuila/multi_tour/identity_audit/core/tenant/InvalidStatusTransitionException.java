package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** An action that the current status of the tenant does not allow (a 422 INVALID_STATUS_TRANSITION). */
public class InvalidStatusTransitionException extends RuntimeException {

  public InvalidStatusTransitionException(TenantStatus from, String action) {
    super("A " + from + " tenant cannot " + action);
  }

  public TenantStatus from() {
    return null;
  }

  public String action() {
    return null;
  }
}
