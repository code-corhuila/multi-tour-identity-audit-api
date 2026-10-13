package co.edu.corhuila.multi_tour.identity_audit.core.security;

/** The caller's role or tenant does not allow the operation (a 403); the route judges the role too, this is the core's own. */
public class CallerNotAllowedException extends RuntimeException {

  public CallerNotAllowedException() {
    super("The token does not allow this");
  }
}