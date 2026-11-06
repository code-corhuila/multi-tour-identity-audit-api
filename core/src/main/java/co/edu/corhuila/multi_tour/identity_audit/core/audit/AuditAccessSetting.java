package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import java.time.Instant;
import java.util.List;

/**
 * Whether an optional role of a tenant may consult the audit of that tenant, and who changed it last and when (HU-SEC-006
 * R3 (a), PDR section 9). A role that was never set has no access and no author.
 */
public record AuditAccessSetting(String role, boolean enabled, String changedBy, Instant changedAt) {

  /** The roles a tenant may open its audit to, in the order they are listed. */
  public static final List<String> OPTIONAL_ROLES = List.of("MANAGER", "ACCOUNTANT", "ANALYST");

  public static AuditAccessSetting neverSet(String role) {
    return new AuditAccessSetting(role, false, null, null);
  }

  /** Whether somebody ever wrote this setting, which tells the store to replace it and not to create it. */
  public boolean wasSet() {
    return changedAt != null;
  }
}
