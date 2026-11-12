package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ChangeTenantLocalization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant Administrator changes the zone and the country of their own tenant, the one in the token: the path and the body name no tenant.
 * The role is judged before this runs (spec 010, HU-IDA-004).
 */
@RestController
@RequestMapping("/api/v1/identity-audit/tenant/localization")
class LocalizationController {

  private final ChangeTenantLocalization change;

  LocalizationController(ChangeTenantLocalization change) {
    this.change = change;
  }

  /** Nothing undeclared is allowed in the body ({@code fail-on-unknown-properties}), a tenant id included. */
  record LocalizationRequest(String timeZone, String country) {}

  @PutMapping
  TenantResponse change(HttpServletRequest request, @RequestBody LocalizationRequest body) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return TenantResponse.of(change.execute(
        new ChangeTenantLocalization.Command(caller, body.timeZone(), body.country(), CorrelationIdFilter.current())));
  }
}
