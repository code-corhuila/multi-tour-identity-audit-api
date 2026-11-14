package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The state of the cash boxes of a tenant could not be read, so a change that depends on it is not made. */
public final class CashBoxesUnavailableException extends RuntimeException {

  public CashBoxesUnavailableException() {
    super("The state of the cash boxes could not be read");
  }
}
