package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailedStep;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;

/**
 * A tenant as the validator of the database expects it (the fields of {@code multi-tour-identity-audit-db}):
 * written by hand and not by a mapping layer, which would add a {@code _class} field that the validator
 * refuses. A field a tenant does not have is left out.
 */
final class TenantDocuments {

  private TenantDocuments() {}

  static Document toDocument(Tenant tenant) {
    Document document =
        new Document("_id", tenant.tenantId())
            .append("identifier", tenant.identifier())
            .append("commercialName", tenant.commercialName())
            .append("tenantStatus", tenant.status().name())
            .append("timeZone", tenant.timeZone())
            .append("country", tenant.country())
            .append("createdAt", Date.from(tenant.createdAt()))
            .append("updatedAt", Date.from(tenant.updatedAt()));
    putIfPresent(document, "firstAdministratorMembershipId", tenant.firstAdministratorMembershipId());
    putIfPresent(document, "statusChangedAt", tenant.statusChangedAt() == null ? null : Date.from(tenant.statusChangedAt()));
    putIfPresent(document, "statusChangedBy", tenant.statusChangedBy());
    putIfPresent(document, "statusReason", tenant.statusReason());
    putIfPresent(document, "failedStep", tenant.failedStep() == null ? null : tenant.failedStep().name());
    return document;
  }

  static Tenant toTenant(Document document) {
    return new Tenant(
        document.getString("_id"),
        document.getString("identifier"),
        document.getString("commercialName"),
        document.getString("timeZone"),
        document.getString("country"),
        document.getDate("createdAt").toInstant(),
        TenantStatus.valueOf(document.getString("tenantStatus")),
        document.getString("firstAdministratorMembershipId"),
        instant(document.getDate("statusChangedAt")),
        document.getString("statusChangedBy"),
        document.getString("statusReason"),
        document.getString("failedStep") == null ? null : FailedStep.valueOf(document.getString("failedStep")),
        document.getDate("updatedAt").toInstant());
  }

  private static Instant instant(Date date) {
    return date == null ? null : date.toInstant();
  }

  private static void putIfPresent(Document document, String field, Object value) {
    if (value != null) {
      document.append(field, value);
    }
  }
}
