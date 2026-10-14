package co.edu.corhuila.multi_tour.identity_audit.core.security;

import java.util.Set;

/**
 * Who is calling: an account or the name of a repository, its roles and, if it has one, its tenant. The
 * Platform Administrator belongs to no tenant, so that role together with one is not a caller (plan D-03).
 */
public record Caller(String subject, String tenantId, Set<Role> roles) {

  public Caller {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("A caller has a subject");
    }
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("A caller has at least one role");
    }
    roles = Set.copyOf(roles);
    tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId;
    boolean opensARoute = roles.contains(Role.PLATFORM_ADMINISTRATOR) || roles.contains(Role.SERVICE);
    if (opensARoute && roles.stream().anyMatch(r -> r != Role.PLATFORM_ADMINISTRATOR && r != Role.SERVICE)) {
      throw new IllegalArgumentException("The role of the platform or of a service is never mixed with a role of a tenant");
    }
    if (roles.contains(Role.PLATFORM_ADMINISTRATOR) && tenantId != null) {
      throw new IllegalArgumentException("The Platform Administrator has no tenant");
    }
  }
}
