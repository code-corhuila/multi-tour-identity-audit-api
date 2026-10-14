package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The four states of a tenant (PDR section 16, ADR-004 D3). */
public enum TenantStatus {
  PENDING,
  ACTIVE,
  INACTIVE,
  FAILED
}
