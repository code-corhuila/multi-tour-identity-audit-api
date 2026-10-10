package co.edu.corhuila.multi_tour.identity_audit.core.security;

import java.util.Set;

/** Who is calling: an account or the name of a repository, its roles and, if it has one, its tenant. */
public record Caller(String subject, String tenantId, Set<Role> roles) {}
