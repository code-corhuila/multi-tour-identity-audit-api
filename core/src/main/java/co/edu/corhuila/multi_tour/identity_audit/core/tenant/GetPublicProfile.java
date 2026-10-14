package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * The public profile behind the link /t/{identifier}. Whatever is not a tenant that finished its onboarding,
 * or is not written like an identifier, is "not found": the link says nothing of a pending, failed or unknown one.
 */
public final class GetPublicProfile {

  private final TenantRepository tenants;

  public GetPublicProfile(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public PublicTenantProfile execute(String identifier) {
    if (!Tenant.isIdentifier(identifier)) {
      throw new TenantNotFoundException("with that identifier");
    }
    return tenants.findByIdentifier(identifier)
        .filter(t -> t.status() == TenantStatus.ACTIVE || t.status() == TenantStatus.INACTIVE)
        .map(t -> new PublicTenantProfile(t.commercialName(), t.identifier(), t.status(), t.visualIdentity()))
        .orElseThrow(() -> new TenantNotFoundException("with that identifier"));
  }
}
