package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** A rule of the business that the request breaks though its data is valid. */
public class BusinessRuleViolationException extends RuntimeException {

  public BusinessRuleViolationException(String message) {
    super(message);
  }
}