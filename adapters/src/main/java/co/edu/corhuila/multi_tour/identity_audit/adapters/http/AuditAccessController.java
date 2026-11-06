package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditAccessSetting;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.ChangeAuditAccess;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.ConsultAuditAccess;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant Administrator opens the audit of their own tenant to an optional role, the tenant being the one in the token:
 * the path and the body name none. The role is judged before this runs.
 */
@RestController
@RequestMapping("/api/v1/identity-audit/tenant/audit-access")
class AuditAccessController {

  private final ConsultAuditAccess consult;
  private final ChangeAuditAccess change;

  AuditAccessController(ConsultAuditAccess consult, ChangeAuditAccess change) {
    this.consult = consult;
    this.change = change;
  }

  /** Nothing undeclared is allowed in the body ({@code fail-on-unknown-properties}), a tenant id included. */
  record AuditAccessRequest(Boolean enabled) {}

  record AuditAccessResponse(String role, boolean enabled, String changedBy, Instant changedAt) {
    static AuditAccessResponse of(AuditAccessSetting setting) {
      return new AuditAccessResponse(setting.role(), setting.enabled(), setting.changedBy(), setting.changedAt());
    }
  }

  record AuditAccessListing(List<AuditAccessResponse> data) {}

  @GetMapping
  AuditAccessListing list(HttpServletRequest request) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return new AuditAccessListing(consult.execute(caller).stream().map(AuditAccessResponse::of).toList());
  }

  @PutMapping("/{role}")
  AuditAccessResponse set(HttpServletRequest request, @PathVariable String role, @RequestBody AuditAccessRequest body) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return AuditAccessResponse.of(change.execute(caller, role, body.enabled()));
  }
}
