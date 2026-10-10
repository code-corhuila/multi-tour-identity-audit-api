package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailedStep;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.client.model.Filters;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.bson.conversions.Bson;

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

  /** The tenant is still as it was when the change was decided: every field that a change can touch is the same. */
  static Bson stillIs(Tenant expected) {
    return Filters.and(
        Filters.eq("_id", expected.tenantId()),
        Filters.eq("tenantStatus", expected.status().name()),
        Filters.eq("updatedAt", Date.from(expected.updatedAt())),
        Filters.eq("firstAdministratorMembershipId", expected.firstAdministratorMembershipId()),
        Filters.eq("statusChangedAt", expected.statusChangedAt() == null ? null : Date.from(expected.statusChangedAt())),
        Filters.eq("statusChangedBy", expected.statusChangedBy()),
        Filters.eq("statusReason", expected.statusReason()),
        Filters.eq("failedStep", expected.failedStep() == null ? null : expected.failedStep().name()));
  }

  /** An event waiting to be delivered, with the full new state of the tenant as its payload (no credentials). */
  static Document toEventDocument(TenantEvent event) {
    Tenant tenant = event.tenant();
    Document payload = toDocument(tenant);
    payload.remove("_id");
    payload.put("tenantId", tenant.tenantId());
    Date at = Date.from(event.occurredAt());
    Document document =
        new Document("_id", event.eventId())
            .append("eventType", event.type().eventType())
            .append("eventVersion", 1)
            .append("tenantId", tenant.tenantId())
            .append("aggregateId", tenant.tenantId())
            .append("actorId", event.actor().strip());
    putIfPresent(document, "reason", tenant.statusReason());
    return document.append("payload", payload).append("correlationId", event.correlationId())
        .append("occurredAt", at).append("status", "PENDING").append("attempts", 0).append("nextAttemptAt", at);
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
