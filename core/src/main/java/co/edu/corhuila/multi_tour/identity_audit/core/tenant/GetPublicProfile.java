package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The public profile behind the link /t/{identifier}. */
public final class GetPublicProfile {

  public GetPublicProfile(TenantRepository tenants) {}

  public PublicTenantProfile execute(String identifier) {
    throw new UnsupportedOperationException();
  }
}
