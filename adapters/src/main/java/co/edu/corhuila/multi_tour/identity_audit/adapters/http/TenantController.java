package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConsultTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ListTenants;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the Platform Administrator reads of the tenants; the role is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/tenants")
class TenantController {

  private static final Set<String> FILTERS = Set.of("page", "limit", "status");

  private final ListTenants list;
  private final ConsultTenant consult;

  TenantController(ListTenants list, ConsultTenant consult) {
    this.list = list;
    this.consult = consult;
  }

  public record Meta(int page, int limit, long total, int totalPages) {}

  public record TenantPage(List<TenantResponse> data, Meta meta) {}

  @GetMapping
  TenantPage list(HttpServletRequest request) {
    Map<String, String[]> sent = request.getParameterMap();
    // a filter that is not declared, or one sent twice, is a refusal and not something to ignore
    if (!FILTERS.containsAll(sent.keySet()) || sent.values().stream().anyMatch(values -> values.length != 1)) {
      throw new InvalidDataException("Only page, limit and status are accepted, each once");
    }
    Page<Tenant> found = list.execute(new ListTenants.Query(
        status(request.getParameter("status")), number(request.getParameter("page"), 1),
        number(request.getParameter("limit"), 20)));
    return new TenantPage(
        found.data().stream().map(TenantResponse::of).toList(),
        new Meta(found.page(), found.limit(), found.total(), found.totalPages()));
  }

  @GetMapping("/{tenantId}")
  TenantResponse consult(@PathVariable String tenantId) {
    return TenantResponse.of(consult.execute(tenantId));
  }

  private static int number(String value, int byDefault) {
    if (value == null) {
      return byDefault;
    }
    // plain digits only: parseInt would also take a sign and the digits of other scripts
    if (!value.matches("[0-9]{1,9}")) {
      throw new InvalidDataException("The page and the limit are whole numbers");
    }
    return Integer.parseInt(value);
  }

  private static TenantStatus status(String value) {
    if (value == null) {
      return null;
    }
    try {
      return TenantStatus.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("The status is not one of the allowed values");
    }
  }
}
