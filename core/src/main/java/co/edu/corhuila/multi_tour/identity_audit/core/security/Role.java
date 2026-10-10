package co.edu.corhuila.multi_tour.identity_audit.core.security;

/**
 * The roles of a token. Only PLATFORM_ADMINISTRATOR and SERVICE open a route here; the others are read so that a user of a
 * tenant is refused with a 403 and not with the 401 of a token the service does not understand.
 */
public enum Role {
  PLATFORM_ADMINISTRATOR,
  SERVICE,
  FINAL_CUSTOMER,
  TENANT_ADMINISTRATOR,
  OPERATIONAL_COLLABORATOR,
  MANAGER,
  ACCOUNTANT,
  ANALYST
}
