package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The operation and the Idempotency-Key (8 to 128 characters) of a creation that may be sent twice. */
public record RepeatKey(String operation, String key) {

  public RepeatKey {
    if (operation == null || operation.isBlank()) {
      throw new InvalidDataException("The operation of a repeat key is required");
    }
    if (key == null || key.length() < 8 || key.length() > 128 || key.chars().anyMatch(Character::isWhitespace)) {
      throw new InvalidDataException("The Idempotency-Key is 8 to 128 characters without spaces");
    }
  }
}
