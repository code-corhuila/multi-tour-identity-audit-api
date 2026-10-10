package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantResponse(
    String tenantId,
    String identifier,
    String commercialName,
    String tenantStatus,
    String timeZone,
    String country,
    String firstAdministratorMembershipId,
    Instant statusChangedAt,
    String statusChangedBy,
    String statusReason,
    String failedStep,
    Instant createdAt,
    Instant updatedAt) {

  static TenantResponse of(Tenant tenant) {
    return new TenantResponse(
        tenant.tenantId(), tenant.identifier(), tenant.commercialName(), tenant.status().name(), tenant.timeZone(),
        tenant.country(), tenant.firstAdministratorMembershipId(), tenant.statusChangedAt(), tenant.statusChangedBy(),
        tenant.statusReason(), tenant.failedStep() == null ? null : tenant.failedStep().name(), tenant.createdAt(),
        tenant.updatedAt());
  }
}
