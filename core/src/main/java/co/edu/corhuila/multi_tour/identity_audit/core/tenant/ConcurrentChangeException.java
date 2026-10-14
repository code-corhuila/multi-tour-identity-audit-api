package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The stored tenant is no longer in the status the change was decided on: another request changed it first. */
public class ConcurrentChangeException extends RuntimeException {

  public ConcurrentChangeException(String tenantId) {
    super("The tenant " + tenantId + " changed while this request was deciding");
  }
}
