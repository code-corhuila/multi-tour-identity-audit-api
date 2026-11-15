package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.GetPublicImage;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSlot;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantImage;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logo and the commercial images of a tenant for anyone with its link, no token and no role (spec 012, RN-IDA-API-125). The file
 * goes back with the kind that was proved, and with the headers that keep it an image whatever its bytes: no sniffing, no
 * scripts and no navigation to it as a page.
 */
@RestController
@RequestMapping("/api/v1/identity-audit/public/tenants/{identifier}")
class PublicTenantImageController {

  private final GetPublicImage image;

  PublicTenantImageController(GetPublicImage image) {
    this.image = image;
  }

  @GetMapping("/logo")
  ResponseEntity<byte[]> logo(@PathVariable String identifier) {
    return served(image.execute(identifier, ImageSlot.LOGO, 0));
  }

  @GetMapping("/images/{position}")
  ResponseEntity<byte[]> commercial(@PathVariable String identifier, @PathVariable String position) {
    // a position that is not plain digits names no file, and the public route says nothing more than "not found"
    if (!position.matches("[0-9]{1,9}")) {
      throw new TenantNotFoundException("with that file");
    }
    return served(image.execute(identifier, ImageSlot.IMAGE, Integer.parseInt(position)));
  }

  private static ResponseEntity<byte[]> served(TenantImage file) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.kind().contentType()))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .header("Content-Disposition", "inline")
        .header("Cache-Control", "public, max-age=300")
        .body(file.bytes());
  }
}
