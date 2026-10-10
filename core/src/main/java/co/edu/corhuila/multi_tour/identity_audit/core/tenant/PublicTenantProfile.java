package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** What anyone with the link /t/{identifier} may know of a tenant: its name, its identifier and its status. */
public record PublicTenantProfile(String commercialName, String identifier, TenantStatus status) {}
