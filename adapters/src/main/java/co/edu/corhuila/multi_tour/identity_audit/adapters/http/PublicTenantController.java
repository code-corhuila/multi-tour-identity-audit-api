package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.GetPublicProfile;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSummary;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.PublicTenantProfile;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What anyone with the link /t/{identifier} may read; no token and no role, only these four fields. */
@RestController
@RequestMapping("/api/v1/identity-audit/public/tenants")
class PublicTenantController {

  private final GetPublicProfile profile;

  PublicTenantController(GetPublicProfile profile) {
    this.profile = profile;
  }

  public record PublicImage(int position, String url) {}

  public record PublicProfileResponse(
      String identifier, String commercialName, String tenantStatus, VisualIdentityBody visualIdentity, String logoUrl,
      List<PublicImage> images) {}

  @GetMapping("/{identifier}")
  ResponseEntity<PublicProfileResponse> get(@PathVariable String identifier) {
    PublicTenantProfile found = profile.execute(identifier);
    // the status changes, so no cache may keep an old one
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(new PublicProfileResponse(
            found.identifier(), found.commercialName(), found.status().name(),
            VisualIdentityBody.of(found.visualIdentity()),
            found.logo() == null ? null : url(found.identifier(), "logo", found.logo()),
            found.images().stream().map(i -> new PublicImage(i.position(), url(found.identifier(), "images/" + i.position(), i))).toList()));
  }

  /** The path of a file with a short version (the start of its hash), so a replaced file is not served from a cache. */
  private static String url(String identifier, String what, ImageSummary file) {
    return "/api/v1/identity-audit/public/tenants/" + identifier + "/" + what + "?v=" + file.sha256().substring(0, 8);
  }
}
