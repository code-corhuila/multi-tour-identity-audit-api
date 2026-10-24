package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The stored tenant is no longer in the status the change was decided on: another request changed it first. */
public class ConcurrentChangeException extends RuntimeException {

  public ConcurrentChangeException(String tenantId) {
    this("tenant", tenantId);
  }

  /** Another kind of record that changed while the request was deciding, such as the session cutoff of an account. */
  public ConcurrentChangeException(String what, String id) {
    super("The " + what + " " + id + " changed while this request was deciding");
  }
}
