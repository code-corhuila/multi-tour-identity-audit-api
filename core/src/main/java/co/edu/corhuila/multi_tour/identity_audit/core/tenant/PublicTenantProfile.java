package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * What anyone with the link /t/{identifier} may know of a tenant: its name, its identifier, its status and, if
 * it set one, its visual identity (null otherwise: the platform's default).
 */
public record PublicTenantProfile(
    String commercialName, String identifier, TenantStatus status, VisualIdentity visualIdentity) {}