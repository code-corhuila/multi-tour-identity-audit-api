package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;

/** One page of a listing, newest first, with the total that the meta of the answer needs. */
public record Page<T>(List<T> data, int page, int limit, long total) {

  public Page {
    if (page < 1 || limit < 1 || total < 0) {
      throw new IllegalArgumentException("A page starts at 1, has a limit of at least 1 and a total of 0 or more");
    }
    data = List.copyOf(data);
  }

  public int totalPages() {
    return (int) ((total + limit - 1) / limit);
  }
}
