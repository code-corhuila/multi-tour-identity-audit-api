package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConsultTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ListTenants;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

  public record TenantPage(List<TenantResponse> data, Paging.Meta meta) {}

  @GetMapping
  TenantPage list(HttpServletRequest request) {
    Paging.onlyDeclared(request, FILTERS);
    Page<Tenant> found = list.execute(new ListTenants.Query(
        status(request.getParameter("status")), Paging.number(request.getParameter("page"), 1),
        Paging.number(request.getParameter("limit"), 20)));
    return new TenantPage(
        found.data().stream().map(TenantResponse::of).toList(),
        Paging.Meta.of(found));
  }

  @GetMapping("/{tenantId}")
  TenantResponse consult(@PathVariable String tenantId) {
    return TenantResponse.of(consult.execute(tenantId));
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
