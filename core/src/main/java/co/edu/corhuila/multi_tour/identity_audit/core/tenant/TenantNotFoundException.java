package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * No tenant with that id or identifier (a 404). The message is fixed and says nothing of what was asked: the
 * caller's own text is not reflected, and a tenant that exists for someone else looks like one that does not.
 */
public class TenantNotFoundException extends RuntimeException {

  public TenantNotFoundException(String what) {
    super("There is no such tenant");
  }
}
