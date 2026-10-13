package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.SetTenantVisualIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant Administrator sets the colors of their own tenant, the one in the token: the path and the body
 * name no tenant. The role is judged before this runs.
 */
@RestController
@RequestMapping("/api/v1/identity-audit/tenant/visual-identity")
class VisualIdentityController {

  private final SetTenantVisualIdentity set;

  VisualIdentityController(SetTenantVisualIdentity set) {
    this.set = set;
  }

  /** Nothing undeclared is allowed in the body ({@code fail-on-unknown-properties}), a tenant id included. */
  record VisualIdentityRequest(String primaryColor, String secondaryColor) {}

  @PutMapping
  TenantResponse set(HttpServletRequest request, @RequestBody VisualIdentityRequest body) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return TenantResponse.of(set.execute(caller, body.primaryColor(), body.secondaryColor()));
  }
}