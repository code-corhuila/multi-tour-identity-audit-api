package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * A file of a tenant for anyone with its link, no sign-in. A tenant that is unknown or has not finished its onboarding, an
 * identifier that is not one and a place with no file are all "not found"; an inactive tenant is served as it is, so its notice
 * can show its logo.
 */
public final class GetPublicImage {

  private final TenantRepository tenants;
  private final TenantImages images;

  public GetPublicImage(TenantRepository tenants, TenantImages images) {
    this.tenants = tenants;
    this.images = images;
  }

  public TenantImage execute(String identifier, ImageSlot slot, int position) {
    if (!Tenant.isIdentifier(identifier)) {
      throw new TenantNotFoundException("with that identifier");
    }
    Tenant tenant = tenants.findByIdentifier(identifier)
        .filter(t -> t.status() == TenantStatus.ACTIVE || t.status() == TenantStatus.INACTIVE)
        .orElseThrow(() -> new TenantNotFoundException("with that identifier"));
    return images.find(tenant.tenantId(), slot, position).orElseThrow(() -> new TenantNotFoundException("with that file"));
  }
}
