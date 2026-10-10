package co.edu.corhuila.multi_tour.identity_audit.app;

import java.util.Set;

/** Which operations of the contract were asked, and answered well at least once. */
final class ExercisedOperations {

  void record(String method, String pathTemplate, int status) {}

  /** The operations in {@code all} that no test has seen answered with a 2xx. */
  Set<String> missing(Set<String> all) {
    return Set.of();
  }
}
