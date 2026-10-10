package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** No tenant with that id or identifier (a 404). */
public class TenantNotFoundException extends RuntimeException {

  public TenantNotFoundException(String what) {
    super("There is no tenant " + what);
  }
}
