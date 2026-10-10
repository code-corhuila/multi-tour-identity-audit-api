package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * Data that the core refuses (a 400 VALIDATION_ERROR). It is the only {@link IllegalArgumentException} whose
 * message is written for the caller: any other one, from the JDK or a library, is a failure to hide.
 */
public class InvalidDataException extends IllegalArgumentException {

  public InvalidDataException(String message) {
    super(message);
  }
}
