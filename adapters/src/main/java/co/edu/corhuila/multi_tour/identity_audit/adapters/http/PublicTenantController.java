package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.GetPublicProfile;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.PublicTenantProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What anyone with the link /t/{identifier} may read; no token and no role, only these three fields. */
@RestController
@RequestMapping("/api/v1/identity-audit/public/tenants")
class PublicTenantController {

  private final GetPublicProfile profile;

  PublicTenantController(GetPublicProfile profile) {
    this.profile = profile;
  }

  public record PublicProfileResponse(String identifier, String commercialName, String tenantStatus) {}

  @GetMapping("/{identifier}")
  ResponseEntity<PublicProfileResponse> get(@PathVariable String identifier) {
    PublicTenantProfile found = profile.execute(identifier);
    // the status changes, so no cache may keep an old one
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(new PublicProfileResponse(found.identifier(), found.commercialName(), found.status().name()));
  }
}
