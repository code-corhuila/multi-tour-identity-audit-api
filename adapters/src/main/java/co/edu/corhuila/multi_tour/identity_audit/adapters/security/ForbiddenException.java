package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

/** A valid token whose role does not allow the route (a 403). */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException() {
    super("The token does not allow this");
  }
}
