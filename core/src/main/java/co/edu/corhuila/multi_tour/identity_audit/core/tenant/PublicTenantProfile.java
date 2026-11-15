package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.List;

/**
 * What anyone with the link /t/{identifier} may know of a tenant: its name, its identifier, its status and, if
 * it set them, its visual identity, its logo and its commercial images (null, or empty, otherwise: the platform's
 * default). The files are named by their place and their hash, never by their bytes.
 */
public record PublicTenantProfile(
    String commercialName, String identifier, TenantStatus status, VisualIdentity visualIdentity, ImageSummary logo,
    List<ImageSummary> images) {}
