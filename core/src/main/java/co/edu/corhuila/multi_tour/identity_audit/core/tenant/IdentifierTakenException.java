package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** A live tenant already has the identifier (a 422 BUSINESS_RULE_VIOLATION that names it). */
public class IdentifierTakenException extends RuntimeException {

  private final String identifier;

  public IdentifierTakenException(String identifier) {
    super("The identifier " + identifier + " is already used");
    this.identifier = identifier;
  }

  public String identifier() { return identifier; }
}
