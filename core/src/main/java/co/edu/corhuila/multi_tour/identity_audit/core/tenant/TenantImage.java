package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.time.Instant;

/** One file of a tenant: its kind is the one proved by the bytes and its hash is made when it is saved. */
public record TenantImage(
    String tenantId, ImageSlot slot, int position, ImageKind kind, byte[] bytes, String sha256, String updatedBy, Instant updatedAt) {

  public int sizeBytes() {
    return bytes.length;
  }
}
