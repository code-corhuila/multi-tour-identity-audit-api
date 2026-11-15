package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;
import java.util.Optional;

/** Where the files of the tenants are kept: one per tenant, slot and position. */
public interface TenantImages {

  /** Keeps the file, replacing the one at its place if there is one, in a single write. */
  void save(TenantImage image);

  /** Takes the file away; there being none is not an error. */
  void remove(String tenantId, ImageSlot slot, int position);

  /** The file at a place, with its bytes. */
  Optional<TenantImage> find(String tenantId, ImageSlot slot, int position);

  /** The files of a tenant without their bytes: the logo first, then the images by position. */
  List<ImageSummary> list(String tenantId);
}
