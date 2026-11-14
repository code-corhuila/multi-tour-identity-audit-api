package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;

/**
 * The public profile behind the link /t/{identifier}. Whatever is not a tenant that finished its onboarding,
 * or is not written like an identifier, is "not found": the link says nothing of a pending, failed or unknown one.
 */
public final class GetPublicProfile {

  private final TenantRepository tenants;
  private final TenantImages images;

  public GetPublicProfile(TenantRepository tenants, TenantImages images) {
    this.tenants = tenants;
    this.images = images;
  }

  public PublicTenantProfile execute(String identifier) {
    if (!Tenant.isIdentifier(identifier)) {
      throw new TenantNotFoundException("with that identifier");
    }
    Tenant tenant = tenants.findByIdentifier(identifier)
        .filter(t -> t.status() == TenantStatus.ACTIVE || t.status() == TenantStatus.INACTIVE)
        .orElseThrow(() -> new TenantNotFoundException("with that identifier"));
    List<ImageSummary> files = images.list(tenant.tenantId());
    ImageSummary logo = files.stream().filter(f -> f.slot() == ImageSlot.LOGO).findFirst().orElse(null);
    List<ImageSummary> commercial = files.stream().filter(f -> f.slot() == ImageSlot.IMAGE).toList();
    return new PublicTenantProfile(tenant.commercialName(), tenant.identifier(), tenant.status(), tenant.visualIdentity(), logo, commercial);
  }
}
