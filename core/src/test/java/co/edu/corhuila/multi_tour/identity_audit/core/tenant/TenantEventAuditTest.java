package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantEventAuditTest {

  private static final String RECORD = "00000000-0000-4000-8000-0000000000aa";
  private static final String EVENT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String MEMBERSHIP = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);
  private static final Instant T2 = T0.plusSeconds(120);

  private static final Tenant PENDING = Tenant.registerPending(TENANT, "acme-tours", "Acme Tours", "America/Bogota", "CO", T0);

  @Test
  void D_the_creation_goes_from_pending_to_its_first_status_by_the_saga() {
    Tenant active = PENDING.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T1);
    TenantEvent created = new TenantEvent(EVENT, TenantEventType.TENANT_CREATED, active, "onboard-tenant", "corr-1", T1);

    AuditRecord record = created.toAuditRecord(RECORD, PENDING);

    assertThat(record.auditRecordId()).isEqualTo(RECORD);
    assertThat(record.eventId()).isEqualTo(EVENT);
    assertThat(record.tenantId()).isEqualTo(TENANT);
    assertThat(record.actorId()).isEqualTo("onboard-tenant");
    assertThat(record.action()).isEqualTo("TENANT_CREATED");
    assertThat(record.affectedRecordId()).isEqualTo("tenants/" + TENANT);
    assertThat(record.previousValue()).isEqualTo(Map.of("tenantStatus", "PENDING"));
    assertThat(record.newValue()).isEqualTo(Map.of("tenantStatus", "ACTIVE"));
    assertThat(record.recordedAt()).isEqualTo(T1);
  }

  @Test
  void D_an_inactivation_and_a_reactivation_keep_who_why_and_the_status_before_and_after() {
    Tenant active = PENDING.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T1);
    Tenant inactive = active.inactivate("platform-admin", "Contract ended", T2);
    TenantEvent off = new TenantEvent(EVENT, TenantEventType.TENANT_INACTIVATED, inactive, "platform-admin", "corr-2", T2);

    AuditRecord record = off.toAuditRecord(RECORD, active);

    assertThat(record.action()).isEqualTo("TENANT_INACTIVATED");
    assertThat(record.actorId()).isEqualTo("platform-admin");
    assertThat(record.reason()).isEqualTo("Contract ended");
    assertThat(record.previousValue()).isEqualTo(Map.of("tenantStatus", "ACTIVE"));
    assertThat(record.newValue()).isEqualTo(Map.of("tenantStatus", "INACTIVE"));

    Tenant back = inactive.reactivate("platform-admin", "Paid", T2.plusSeconds(60));
    AuditRecord on = new TenantEvent("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", TenantEventType.TENANT_REACTIVATED, back,
        "platform-admin", "corr-3", back.updatedAt()).toAuditRecord(RECORD, inactive);
    assertThat(on.action()).isEqualTo("TENANT_REACTIVATED");
    assertThat(on.previousValue()).isEqualTo(Map.of("tenantStatus", "INACTIVE"));
    assertThat(on.newValue()).isEqualTo(Map.of("tenantStatus", "ACTIVE"));
  }
}
