package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The repeat key was stored by another request that arrived first. */
public class RepeatKeyUsedException extends RuntimeException {

  public RepeatKeyUsedException(RepeatKey key) {
    super("The repeat key of " + key.operation() + " is already stored");
  }
}
