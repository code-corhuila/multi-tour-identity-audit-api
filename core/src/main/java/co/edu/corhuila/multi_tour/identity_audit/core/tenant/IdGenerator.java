package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** Where the ids of tenants and events come from. */
public interface IdGenerator {

  /** A new lowercase UUID. */
  String newId();
}
