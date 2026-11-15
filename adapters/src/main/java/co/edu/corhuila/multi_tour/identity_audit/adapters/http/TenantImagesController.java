package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSlot;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RemoveTenantImage;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.SaveTenantImage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant Administrator saves and removes the logo and the commercial images of their own tenant, the one in the token: the
 * path and the body name no tenant (spec 012). The role is judged before this runs; the body may be up to 1.5 MB on the two
 * routes that receive a file.
 */
@RestController
@RequestMapping("/api/v1/identity-audit/tenant")
class TenantImagesController {

  private final SaveTenantImage save;
  private final RemoveTenantImage remove;

  TenantImagesController(SaveTenantImage save, RemoveTenantImage remove) {
    this.save = save;
    this.remove = remove;
  }

  /** Nothing undeclared is allowed in the body ({@code fail-on-unknown-properties}), a tenant id included. */
  record ImageRequest(String contentType, String data) {}

  @PutMapping("/logo")
  TenantResponse saveLogo(HttpServletRequest request, @RequestBody ImageRequest body) {
    return TenantResponse.of(save.execute(caller(request), ImageSlot.LOGO, 0, body.contentType(), body.data()));
  }

  @DeleteMapping("/logo")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeLogo(HttpServletRequest request) {
    remove.execute(caller(request), ImageSlot.LOGO, 0);
  }

  @PutMapping("/images/{position}")
  TenantResponse saveImage(HttpServletRequest request, @PathVariable String position, @RequestBody ImageRequest body) {
    return TenantResponse.of(save.execute(caller(request), ImageSlot.IMAGE, position(position), body.contentType(), body.data()));
  }

  @DeleteMapping("/images/{position}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeImage(HttpServletRequest request, @PathVariable String position) {
    remove.execute(caller(request), ImageSlot.IMAGE, position(position));
  }

  private static Caller caller(HttpServletRequest request) {
    return (Caller) request.getAttribute(JwtAuthFilter.CALLER);
  }

  /** Plain digits only: a sign, a dot or the digits of another script are not a position. */
  private static int position(String value) {
    if (!value.matches("[0-9]{1,9}")) {
      throw new InvalidDataException("position", "The position is a whole number from 0 to 4");
    }
    return Integer.parseInt(value);
  }
}
