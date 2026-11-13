package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRecordTest {

  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private static final String EVENT = "00000000-0000-4000-8000-000000000002";
  private static final String TENANT = "00000000-0000-4000-8000-000000000003";
  private static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

  private static AuditRecord record(String id, String eventId, String actor, String action, String affected, Instant at) {
    return new AuditRecord(id, eventId, TENANT, actor, action, affected, null, null, null, null, null, at);
  }

  private static AuditRecord valid() {
    return record(ID, EVENT, "actor-1", "SPECIAL_PERMISSION_GRANTED", "accounts/" + ID, AT);
  }

  @Test
  void D_an_audit_record_requires_actor_action_affected_record_and_time() {
    assertThat(valid().action()).isEqualTo("SPECIAL_PERMISSION_GRANTED");

    assertThatThrownBy(() -> record(ID, EVENT, null, "ACTION", "accounts/1", AT)).as("no actor").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, " ", "ACTION", "accounts/1", AT)).as("a blank actor").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "a".repeat(101), "ACTION", "accounts/1", AT)).as("an actor over 100").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", null, "accounts/1", AT)).as("no action").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "special_permission", "accounts/1", AT)).as("an action not in SCREAMING_SNAKE_CASE").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "A".repeat(65), "accounts/1", AT)).as("an action over 64").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "ACTION", null, AT)).as("no affected record").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "ACTION", "x".repeat(201), AT)).as("an affected record over 200").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "ACTION", "accounts/1", null)).as("no time").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record("not-a-uuid", EVENT, "actor-1", "ACTION", "accounts/1", AT)).as("a bad record id").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, null, "actor-1", "ACTION", "accounts/1", AT)).as("no event id").isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_every_limit_is_kept_at_its_exact_value_and_refused_one_above() {
    AuditRecord atTheLimits = new AuditRecord(ID, EVENT, TENANT, "a".repeat(100), "A".repeat(64), "x".repeat(200), "r".repeat(500),
        null, null, "c".repeat(64), "f".repeat(100), AT);
    assertThat(atTheLimits.functionalProcessReference()).hasSize(100);

    assertThatThrownBy(() -> new AuditRecord(ID, EVENT, TENANT, "actor-1", "ACTION", "accounts/1", null, null, null, null, "f".repeat(101), AT))
        .as("a process over 100").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditRecord(ID, EVENT, TENANT, "actor-1", "ACTION", "accounts/1", " ", null, null, null, null, AT))
        .as("a blank reason").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record("0000000A-0000-4000-8000-00000000000A", EVENT, "actor-1", "ACTION", "accounts/1", AT)).as("an upper-case id").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, "not-a-uuid", "actor-1", "ACTION", "accounts/1", AT)).as("a bad event id").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "1ACTION", "accounts/1", AT)).as("an action that starts with a digit").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record(ID, EVENT, "actor-1", "_ACTION", "accounts/1", AT)).as("an action that starts with an underscore").isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_an_audit_record_keeps_the_optional_fields_only_within_their_limits() {
    AuditRecord full = new AuditRecord(ID, EVENT, null, "actor-1", "TENANT_INACTIVATED", "tenants/" + TENANT, "Contract ended",
        Map.of("tenantStatus", "ACTIVE"), Map.of("tenantStatus", "INACTIVE"), "ADMIN_PORTAL", "TENANT_LIFECYCLE", AT);
    assertThat(full.tenantId()).as("an action of the platform has no tenant").isNull();
    assertThat(full.reason()).isEqualTo("Contract ended");

    assertThatThrownBy(() -> new AuditRecord(ID, EVENT, TENANT, "actor-1", "ACTION", "accounts/1", "r".repeat(501), null, null, null, null, AT))
        .as("a reason over 500").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditRecord(ID, EVENT, TENANT, "actor-1", "ACTION", "accounts/1", null, null, null, "c".repeat(65), null, AT))
        .as("a channel over 64").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditRecord(ID, EVENT, "not-a-uuid", "actor-1", "ACTION", "accounts/1", null, null, null, null, null, AT))
        .as("a tenant that is not a uuid").isInstanceOf(InvalidDataException.class);
  }
}
