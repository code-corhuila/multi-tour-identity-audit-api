package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;

/** One page of a listing, newest first, with the total that the meta of the answer needs. */
public record Page<T>(List<T> data, int page, int limit, long total) {

  public int totalPages() {
    return 0;
  }
}
