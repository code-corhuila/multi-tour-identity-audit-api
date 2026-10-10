package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

/** What every listing reads from the query: the filters it declares, each once, and plain whole numbers. */
final class Paging {

  private Paging() {}

  /** The meta of a listing answer. */
  public record Meta(int page, int limit, long total, int totalPages) {

    static Meta of(Page<?> page) {
      return new Meta(page.page(), page.limit(), page.total(), page.totalPages());
    }
  }

  /** A filter that is not declared, or one sent twice, is a refusal and not something to ignore. */
  static void onlyDeclared(HttpServletRequest request, Set<String> declared) {
    Map<String, String[]> sent = request.getParameterMap();
    if (!declared.containsAll(sent.keySet()) || sent.values().stream().anyMatch(values -> values.length != 1)) {
      throw new InvalidDataException("Only " + String.join(", ", declared.stream().sorted().toList()) + " are accepted, each once");
    }
  }

  static int number(String value, int byDefault) {
    if (value == null) {
      return byDefault;
    }
    // plain digits only: parseInt would also take a sign and the digits of other scripts
    if (!value.matches("[0-9]{1,9}")) {
      throw new InvalidDataException("The page and the limit are whole numbers");
    }
    return Integer.parseInt(value);
  }
}
