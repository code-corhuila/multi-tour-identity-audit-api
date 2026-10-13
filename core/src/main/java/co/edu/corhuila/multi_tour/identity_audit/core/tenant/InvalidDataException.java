package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * Data that the core refuses (a 400 VALIDATION_ERROR). It is the only {@link IllegalArgumentException} whose
 * message is written for the caller: any other one, from the JDK or a library, is a failure to hide. When one
 * field of the request is to blame, {@link #field()} names it.
 */
public class InvalidDataException extends IllegalArgumentException {

  private final String field;

  public InvalidDataException(String message) {
    this(null, message);
  }

  public InvalidDataException(String field, String message) {
    super(message);
    this.field = field;
  }

  /** The field of the request to blame, or null when the refusal is not about one field. */
  public String field() { return field; }
}