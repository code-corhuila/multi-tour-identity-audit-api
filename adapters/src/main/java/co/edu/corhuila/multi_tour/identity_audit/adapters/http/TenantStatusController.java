package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InactivateTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ReactivateTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Platform Administrator stops and restarts a tenant; the role is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/tenants/{tenantId}")
class TenantStatusController {

  private final InactivateTenant inactivate;
  private final ReactivateTenant reactivate;

  TenantStatusController(InactivateTenant inactivate, ReactivateTenant reactivate) {
    this.inactivate = inactivate;
    this.reactivate = reactivate;
  }

  record ReasonRequest(String reason) {}

  @PostMapping("/inactivate")
  TenantResponse inactivate(
      @PathVariable String tenantId, HttpServletRequest request, @RequestBody ReasonRequest body) {
    return TenantResponse.of(inactivate.execute(new InactivateTenant.Command(
        tenantId, actor(request), body.reason(), CorrelationIdFilter.current())));
  }

  @PostMapping("/reactivate")
  TenantResponse reactivate(
      @PathVariable String tenantId, HttpServletRequest request, @RequestBody ReasonRequest body) {
    return TenantResponse.of(reactivate.execute(new ReactivateTenant.Command(
        tenantId, actor(request), body.reason(), CorrelationIdFilter.current())));
  }

  private static String actor(HttpServletRequest request) {
    return ((Caller) request.getAttribute(JwtAuthFilter.CALLER)).subject();
  }
}
