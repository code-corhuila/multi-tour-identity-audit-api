package co.edu.corhuila.multi_tour.identity_audit.core.outbox;

/** No event with that id (a 404); the message is fixed and does not reflect what was asked. */
public class OutboxEventNotFoundException extends RuntimeException {

  public OutboxEventNotFoundException() {
    super("There is no such event");
  }
}
