package co.edu.corhuila.multi_tour.identity_audit.app;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The contract of the service, {@code openapi/openapi.yaml} (ADR-004 D7). Every answer that goes through
 * {@link #filter()} is validated against it, and the operation that was asked is remembered, so that the gate
 * ({@code ContractGateTest}) fails if an operation of the contract was never exercised.
 */
final class Contract {

  private static final String SPEC = Path.of("..", "openapi", "openapi.yaml").toUri().toString();
  private static final OpenApiValidationFilter VALIDATION = new OpenApiValidationFilter(SPEC);
  private static final Set<String> EXERCISED = ConcurrentHashMap.newKeySet();

  private Contract() {}

  /** Validates the answer against the contract and remembers which operation was asked. */
  static Filter filter() {
    return (FilterableRequestSpecification request, FilterableResponseSpecification response, FilterContext context) -> {
      Response answer = VALIDATION.filter(request, response, context);
      EXERCISED.add(request.getMethod() + " " + template(request.getDerivedPath()));
      return answer;
    };
  }

  /** Every operation of the contract, as {@code METHOD /path/{template}}. */
  static Set<String> operations() {
    OpenAPI api = new OpenAPIV3Parser().read(SPEC);
    Set<String> all = new TreeSet<>();
    api.getPaths().forEach((path, item) ->
        item.readOperationsMap().keySet().forEach(method -> all.add(method.name() + " " + path)));
    return all;
  }

  static Set<String> exercised() {
    return new TreeSet<>(EXERCISED);
  }

  /** The path of the contract that a real path belongs to: {@code /tenants/abc} is {@code /tenants/{tenantId}}. */
  private static String template(String path) {
    for (String candidate : operations().stream().map(o -> o.substring(o.indexOf(' ') + 1)).distinct().toList()) {
      if (Pattern.matches(candidate.replaceAll("\\{[^/}]+}", "[^/]+"), path)) {
        return candidate;
      }
    }
    return path;
  }

  /** What the gate reports: the operations of the contract that no test asked. */
  static Set<String> unexercised() {
    Set<String> missing = operations();
    missing.removeAll(EXERCISED);
    return missing;
  }
}
