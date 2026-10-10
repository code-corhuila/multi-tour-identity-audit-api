package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RegisterPendingTenant;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants")
class InternalTenantController {

  private final RegisterPendingTenant register;

  InternalTenantController(RegisterPendingTenant register) {
    this.register = register;
  }

  /** Nothing undeclared is allowed in the body ({@code fail-on-unknown-properties}). */
  record RegisterRequest(String identifier, String commercialName, String timeZone, String country) {}

  @PostMapping
  ResponseEntity<TenantResponse> register(
      @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody RegisterRequest body) {
    var result = register.execute(new RegisterPendingTenant.Command(
        body.identifier(), body.commercialName(), body.timeZone(), body.country(), key));
    TenantResponse response = TenantResponse.of(result.tenant());
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/identity-audit/tenants/" + response.tenantId())).body(response)
        : ResponseEntity.ok(response);
  }
}
