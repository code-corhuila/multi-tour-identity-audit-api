package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditQuery;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.ListPlatformAudit;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.ListTenantAudit;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is read of the audit, read-only: across tenants for the Platform Administrator, and of one tenant for its users. Who may
 * is judged before this runs, by role, and then by the use cases, which know the tenant and the access of each role. A query
 * that is malformed is {@code 400} whoever asks, since it tells nothing about the audit.
 */
@RestController
@RequestMapping("/api/v1/identity-audit")
class AuditController {

  private static final Set<String> TENANT_FILTERS = Set.of("page", "limit", "from", "to", "action");
  private static final Set<String> PLATFORM_FILTERS = Set.of("page", "limit", "from", "to", "action", "tenantId");

  private final ListPlatformAudit platform;
  private final ListTenantAudit tenant;

  AuditController(ListPlatformAudit platform, ListTenantAudit tenant) {
    this.platform = platform;
    this.tenant = tenant;
  }

  record AuditRecordResponse(String auditRecordId, String tenantId, String actorId, String action, String affectedRecordId,
      String reason, Map<String, Object> previousValue, Map<String, Object> newValue, String channelOrModule,
      String functionalProcessReference, Instant recordedAt) {

    static AuditRecordResponse of(AuditRecord r) {
      return new AuditRecordResponse(r.auditRecordId(), r.tenantId(), r.actorId(), r.action(), r.affectedRecordId(), r.reason(),
          r.previousValue(), r.newValue(), r.channelOrModule(), r.functionalProcessReference(), r.recordedAt());
    }
  }

  record AuditPage(List<AuditRecordResponse> data, Paging.Meta meta) {
    static AuditPage of(Page<AuditRecord> found) {
      return new AuditPage(found.data().stream().map(AuditRecordResponse::of).toList(), Paging.Meta.of(found));
    }
  }

  @GetMapping("/audit-records")
  AuditPage platform(HttpServletRequest request) {
    Paging.onlyDeclared(request, PLATFORM_FILTERS);
    return AuditPage.of(platform.execute(caller(request), query(request, request.getParameter("tenantId"))));
  }

  @GetMapping("/tenants/{tenantId}/audit-records")
  AuditPage tenant(HttpServletRequest request, @PathVariable String tenantId) {
    Paging.onlyDeclared(request, TENANT_FILTERS);
    return AuditPage.of(tenant.execute(caller(request), tenantId, query(request, null)));
  }

  /** The same use case and the same filters, with the tenant of the token: a caller with no tenant is refused by the use case. */
  @GetMapping("/tenant/audit-records")
  AuditPage own(HttpServletRequest request) {
    Paging.onlyDeclared(request, TENANT_FILTERS);
    Caller caller = caller(request);
    return AuditPage.of(tenant.execute(caller, caller.tenantId(), query(request, null)));
  }

  private static Caller caller(HttpServletRequest request) {
    return (Caller) request.getAttribute(JwtAuthFilter.CALLER);
  }

  private static AuditQuery query(HttpServletRequest request, String tenantId) {
    return new AuditQuery(tenantId, time("from", request.getParameter("from")), time("to", request.getParameter("to")),
        request.getParameter("action"), Paging.number(request.getParameter("page"), 1), Paging.number(request.getParameter("limit"), 20));
  }

  /** The form the contract names, with a capital T and a Z: a sign, an offset or a lower-case letter is not accepted. */
  private static final Pattern UTC = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z");

  private static Instant time(String field, String value) {
    if (value == null) {
      return null;
    }
    try {
      if (UTC.matcher(value).matches()) {
        return Instant.parse(value);
      }
    } catch (DateTimeParseException notADate) {
      // the same answer as a form that does not match
    }
    throw new InvalidDataException(field, "must be an RFC 3339 date-time in UTC, written with a Z");
  }
}
