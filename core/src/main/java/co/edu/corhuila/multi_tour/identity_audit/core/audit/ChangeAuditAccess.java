package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdGenerator;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Tenant Administrator opens the audit of their own tenant to an optional role, or closes it (HU-IDA-002 AC6). The tenant
 * is the caller's and is never named by the request. A real change keeps its audit record in the same transaction; setting
 * what is already set changes nothing and records nothing.
 */
public final class ChangeAuditAccess {

  private static final Pattern ACCOUNT_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final AuditAccessSettings settings;
  private final Clock clock;
  private final IdGenerator ids;

  public ChangeAuditAccess(AuditAccessSettings settings, Clock clock, IdGenerator ids) {
    this.settings = settings;
    this.clock = clock;
    this.ids = ids;
  }

  /** A race decides again once, as the other changes of the service do. */
  public AuditAccessSetting execute(Caller caller, String role, Boolean enabled) {
    // an account, because the setting keeps who changed it: the subject of a service name is not one. The token carries the
    // account id as the database writes it, in lower case (ADR-004 D5); another spelling is refused, which is the safe failure
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null
        || !ACCOUNT_ID.matcher(caller.subject()).matches()) {
      throw new CallerNotAllowedException();
    }
    if (role == null || !AuditAccessSetting.OPTIONAL_ROLES.contains(role)) {
      throw new InvalidDataException("role", "must be one of " + String.join(", ", AuditAccessSetting.OPTIONAL_ROLES));
    }
    if (enabled == null) {
      throw new InvalidDataException("enabled", "is required");
    }
    try {
      return attempt(caller, role, enabled);
    } catch (ConcurrentChangeException raced) {
      return attempt(caller, role, enabled);
    }
  }

  private AuditAccessSetting attempt(Caller caller, String role, boolean enabled) {
    String tenantId = caller.tenantId();
    AuditAccessSetting current = settings.find(tenantId).stream().filter(s -> s.role().equals(role)).findFirst().orElseThrow();
    if (current.enabled() == enabled) {
      return current;
    }
    // the store keeps milliseconds, so the answer says what was stored
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    AuditAccessSetting next = new AuditAccessSetting(role, enabled, caller.subject(), now);
    // the change has no event of the platform, so its record gets an event id of its own
    AuditRecord audit = new AuditRecord(ids.newId(), ids.newId(), tenantId, caller.subject(),
        enabled ? "AUDIT_ACCESS_ENABLED" : "AUDIT_ACCESS_DISABLED", "tenants/" + tenantId + "/audit-access/" + role, null,
        Map.of("role", role, "enabled", current.enabled()), Map.of("role", role, "enabled", enabled), null, null, now);
    settings.change(tenantId, current, next, audit);
    return next;
  }
}
