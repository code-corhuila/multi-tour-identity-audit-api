package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ListTenants;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What a service reads of the tenants: only the identifier and the status (spec 011); the role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants")
class InternalTenantsController {

  private static final Set<String> FILTERS = Set.of("page", "limit", "status");

  private final ListTenants list;

  InternalTenantsController(ListTenants list) {
    this.list = list;
  }

  public record Item(String tenantId, String status) {}

  public record ServiceTenantPage(List<Item> data, Paging.Meta meta) {}

  @GetMapping
  ServiceTenantPage list(HttpServletRequest request) {
    Paging.onlyDeclared(request, FILTERS);
    Page<Tenant> found = list.execute(new ListTenants.Query(
        status(request.getParameter("status")), Paging.number(request.getParameter("page"), 1),
        Paging.number(request.getParameter("limit"), 20)));
    return new ServiceTenantPage(
        found.data().stream().map(tenant -> new Item(tenant.tenantId(), tenant.status().name())).toList(),
        Paging.Meta.of(found));
  }

  /** ALL is no filter; any other word that is not a status of the list is refused. */
  private static TenantStatus status(String value) {
    if (value == null || value.equals("ALL")) {
      return null;
    }
    if (value.equals("ACTIVE") || value.equals("INACTIVE")) {
      return TenantStatus.valueOf(value);
    }
    throw new InvalidDataException("The status is ACTIVE, INACTIVE or ALL");
  }
}
