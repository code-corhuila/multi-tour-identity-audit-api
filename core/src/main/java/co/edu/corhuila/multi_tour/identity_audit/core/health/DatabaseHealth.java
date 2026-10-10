package co.edu.corhuila.multi_tour.identity_audit.core.health;

/** Whether the database answers; the adapter decides how long it waits and what it keeps. */
@FunctionalInterface
public interface DatabaseHealth {

  boolean isUp();
}
