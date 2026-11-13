package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.RecordPlatformEvent.Outcome;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdGenerator;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecordPlatformEventTest {

  private static final String EVENT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String OTHER_EVENT = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String ADMIN = "5e5e5e5e-5e5e-4e5e-8e5e-5e5e5e5e5e5e";
  private static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

  /** The port in memory: a second record of the same event is refused, as the unique index of the database does. */
  private static final class FakeAuditRecords implements AuditRecords {
    final List<AuditRecord> kept = new ArrayList<>();
    final Set<String> events = new HashSet<>();

    @Override
    public boolean append(AuditRecord record) {
      if (!events.add(record.eventId())) {
        return false;
      }
      kept.add(record);
      return true;
    }
  }

  private final FakeAuditRecords store = new FakeAuditRecords();
  private final IdGenerator ids = () -> "00000000-0000-4000-8000-0000000000aa";
  private final RecordPlatformEvent record = new RecordPlatformEvent(store, ids);

  private static PlatformEvent granted(String eventId, Map<String, Object> payload) {
    return new PlatformEvent(eventId, "SpecialPermissionGranted", TENANT, ACCOUNT, ADMIN, "Needs it for the month", payload, AT);
  }

  @Test
  void A_a_delivered_event_becomes_one_record_with_its_fields() {
    Outcome outcome = record.execute(granted(EVENT, Map.of("permission", "MANAGE_CASH")));

    assertThat(outcome).isEqualTo(Outcome.RECORDED);
    assertThat(store.kept).singleElement().satisfies(r -> {
      assertThat(r.auditRecordId()).isEqualTo("00000000-0000-4000-8000-0000000000aa");
      assertThat(r.eventId()).isEqualTo(EVENT);
      assertThat(r.tenantId()).isEqualTo(TENANT);
      assertThat(r.actorId()).isEqualTo(ADMIN);
      assertThat(r.action()).isEqualTo("SPECIAL_PERMISSION_GRANTED");
      assertThat(r.affectedRecordId()).isEqualTo("accounts/" + ACCOUNT);
      assertThat(r.reason()).isEqualTo("Needs it for the month");
      assertThat(r.newValue()).containsEntry("permission", "MANAGE_CASH");
      assertThat(r.recordedAt()).isEqualTo(AT);
    });
  }

  @Test
  void A_an_event_without_an_actor_is_attributed_to_the_security_service() {
    record.execute(new PlatformEvent(EVENT, "AccountBlocked", TENANT, ACCOUNT, null, null, Map.of("blockedUntil", AT.toString()), AT));

    assertThat(store.kept).singleElement().satisfies(r -> {
      assertThat(r.actorId()).isEqualTo("multi-tour-security");
      assertThat(r.reason()).isNull();
    });
  }

  @Test
  void A_the_optional_role_events_point_at_the_tenant_and_the_others_at_the_account() {
    record.execute(new PlatformEvent(EVENT, "OptionalRoleEnabled", TENANT, TENANT, ADMIN, null, Map.of("role", "ANALYST"), AT));
    record.execute(new PlatformEvent(OTHER_EVENT, "OptionalRoleDisabled", TENANT, TENANT, ADMIN, null, Map.of("role", "ANALYST"), AT));

    assertThat(store.kept).extracting(AuditRecord::affectedRecordId).containsOnly("tenants/" + TENANT);
    assertThat(store.kept).extracting(AuditRecord::action).containsExactly("OPTIONAL_ROLE_ENABLED", "OPTIONAL_ROLE_DISABLED");
  }

  @Test
  void A_only_the_two_optional_role_events_point_at_the_tenant_not_a_name_that_is_part_of_theirs() {
    record.execute(new PlatformEvent(EVENT, "Role", TENANT, ACCOUNT, ADMIN, null, Map.of(), AT));
    record.execute(new PlatformEvent(OTHER_EVENT, "OptionalRole", TENANT, ACCOUNT, ADMIN, null, Map.of(), AT));

    assertThat(store.kept).extracting(AuditRecord::affectedRecordId).containsOnly("accounts/" + ACCOUNT);
  }

  @Test
  void A_the_extended_fields_come_from_the_payload_when_the_event_carries_them() {
    Map<String, Object> payload = Map.of("previousValue", Map.of("status", "ACTIVE"), "channelOrModule", "ADMIN_PORTAL",
        "functionalProcessReference", "COLLABORATOR_LIFECYCLE", "status", "INACTIVE");

    record.execute(granted(EVENT, payload));

    assertThat(store.kept).singleElement().satisfies(r -> {
      assertThat(r.previousValue()).isEqualTo(Map.of("status", "ACTIVE"));
      assertThat(r.channelOrModule()).isEqualTo("ADMIN_PORTAL");
      assertThat(r.functionalProcessReference()).isEqualTo("COLLABORATOR_LIFECYCLE");
      assertThat(r.newValue()).containsEntry("status", "INACTIVE");
    });
  }

  @Test
  void A_a_repeat_of_the_event_is_reported_and_keeps_one_record() {
    record.execute(granted(EVENT, Map.of("permission", "MANAGE_CASH")));

    assertThat(record.execute(granted(EVENT, Map.of("permission", "OTHER")))).isEqualTo(Outcome.REPEATED);
    assertThat(store.kept).hasSize(1);
  }

  @Test
  void A_an_event_that_cannot_make_a_record_is_refused_and_writes_nothing() {
    assertThatThrownBy(() -> record.execute(new PlatformEvent(EVENT, "not a name", TENANT, ACCOUNT, ADMIN, null, Map.of(), AT)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record.execute(new PlatformEvent(EVENT, "AccountBlocked", TENANT, ACCOUNT, "a".repeat(101), null, Map.of(), AT)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record.execute(new PlatformEvent(EVENT, "AccountBlocked", TENANT, "not-a-uuid", ADMIN, null, Map.of(), AT)))
        .as("the aggregate id makes the affected record").isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> record.execute(new PlatformEvent(EVENT, "AccountBlocked", TENANT, ACCOUNT, ADMIN, null, Map.of(), null)))
        .isInstanceOf(InvalidDataException.class);
    assertThat(store.kept).isEmpty();
  }
}
