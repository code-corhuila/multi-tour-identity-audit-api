package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuditAccessTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ADMIN = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

  /** The port in memory, for one tenant: the three roles always come back, and a race can be injected before the write. */
  private static final class FakeSettings implements AuditAccessSettings {
    final Map<String, AuditAccessSetting> kept = new LinkedHashMap<>();
    final List<AuditRecord> audits = new ArrayList<>();
    int races;

    @Override
    public List<AuditAccessSetting> find(String tenantId) {
      return AuditAccessSetting.OPTIONAL_ROLES.stream().map(r -> kept.getOrDefault(r, AuditAccessSetting.neverSet(r))).toList();
    }

    @Override
    public void change(String tenantId, AuditAccessSetting expected, AuditAccessSetting next, AuditRecord audit) {
      if (races > 0) {
        races--;
        throw new ConcurrentChangeException("audit access", next.role());
      }
      kept.put(next.role(), next);
      audits.add(audit);
    }
  }

  private final FakeSettings store = new FakeSettings();
  private final AtomicInteger sequence = new AtomicInteger();
  private final ChangeAuditAccess change = new ChangeAuditAccess(store, Clock.fixed(NOW, ZoneOffset.UTC),
      () -> "00000000-0000-4000-8000-%012d".formatted(sequence.incrementAndGet()));
  private final ConsultAuditAccess consult = new ConsultAuditAccess(store);
  private final Caller administrator = new Caller(ADMIN, TENANT, Set.of(Role.TENANT_ADMINISTRATOR));

  @Test
  void A_only_the_tenant_administrator_of_a_tenant_reads_and_changes_the_audit_access() {
    List<Caller> others = List.of(
        new Caller(ADMIN, null, Set.of(Role.PLATFORM_ADMINISTRATOR)),
        new Caller("event-relay", null, Set.of(Role.SERVICE)),
        new Caller(ADMIN, TENANT, Set.of(Role.MANAGER)),
        new Caller(ADMIN, TENANT, Set.of(Role.ACCOUNTANT)),
        new Caller(ADMIN, TENANT, Set.of(Role.OPERATIONAL_COLLABORATOR)),
        new Caller(ADMIN, TENANT, Set.of(Role.FINAL_CUSTOMER)),
        new Caller(ADMIN, null, Set.of(Role.TENANT_ADMINISTRATOR)));

    for (Caller other : others) {
      assertThatThrownBy(() -> consult.execute(other)).as("read by %s", other).isInstanceOf(CallerNotAllowedException.class);
      assertThatThrownBy(() -> change.execute(other, "ANALYST", true)).as("change by %s", other).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(store.kept).isEmpty();
    assertThat(store.audits).isEmpty();
  }

  @Test
  void A_the_three_optional_roles_are_always_listed_and_none_has_the_access_until_it_is_given() {
    assertThat(consult.execute(administrator)).extracting(AuditAccessSetting::role).containsExactly("MANAGER", "ACCOUNTANT", "ANALYST");
    assertThat(consult.execute(administrator)).allSatisfy(s -> {
      assertThat(s.enabled()).isFalse();
      assertThat(s.changedBy()).isNull();
      assertThat(s.changedAt()).isNull();
    });
  }

  @Test
  void A_giving_the_access_keeps_the_setting_and_a_record_with_the_value_before_and_after() {
    AuditAccessSetting given = change.execute(administrator, "ANALYST", true);

    assertThat(given).isEqualTo(new AuditAccessSetting("ANALYST", true, ADMIN, NOW));
    assertThat(consult.execute(administrator)).filteredOn(AuditAccessSetting::enabled).extracting(AuditAccessSetting::role).containsExactly("ANALYST");
    assertThat(store.audits).singleElement().satisfies(r -> {
      assertThat(r.action()).isEqualTo("AUDIT_ACCESS_ENABLED");
      assertThat(r.actorId()).isEqualTo(ADMIN);
      assertThat(r.tenantId()).isEqualTo(TENANT);
      assertThat(r.affectedRecordId()).isEqualTo("tenants/" + TENANT + "/audit-access/ANALYST");
      assertThat(r.previousValue()).isEqualTo(Map.of("role", "ANALYST", "enabled", false));
      assertThat(r.newValue()).isEqualTo(Map.of("role", "ANALYST", "enabled", true));
      assertThat(r.recordedAt()).isEqualTo(NOW);
      assertThat(r.eventId()).as("the change has no event, so it gets one of its own").isNotEqualTo(r.auditRecordId());
    });
  }

  @Test
  void A_removing_the_access_keeps_a_record_of_the_removal() {
    change.execute(administrator, "MANAGER", true);

    AuditAccessSetting removed = change.execute(administrator, "MANAGER", false);

    assertThat(removed.enabled()).isFalse();
    assertThat(store.audits).extracting(AuditRecord::action).containsExactly("AUDIT_ACCESS_ENABLED", "AUDIT_ACCESS_DISABLED");
    assertThat(store.audits.get(1).previousValue()).isEqualTo(Map.of("role", "MANAGER", "enabled", true));
  }

  @Test
  void A_setting_what_is_already_set_changes_nothing_and_records_nothing() {
    change.execute(administrator, "ACCOUNTANT", true);

    AuditAccessSetting again = change.execute(administrator, "ACCOUNTANT", true);
    AuditAccessSetting neverSet = change.execute(administrator, "ANALYST", false);

    assertThat(again).isEqualTo(new AuditAccessSetting("ACCOUNTANT", true, ADMIN, NOW));
    assertThat(neverSet).isEqualTo(AuditAccessSetting.neverSet("ANALYST"));
    assertThat(store.audits).hasSize(1);
  }

  @Test
  void A_a_role_that_is_not_optional_or_a_missing_value_is_refused_and_writes_nothing() {
    for (String bad : new String[] {null, "", "FINAL_CUSTOMER", "TENANT_ADMINISTRATOR", "OPERATIONAL_COLLABORATOR", "manager", "NOBODY"}) {
      assertThatThrownBy(() -> change.execute(administrator, bad, true)).as("role %s", bad).isInstanceOf(InvalidDataException.class);
    }
    assertThatThrownBy(() -> change.execute(administrator, "MANAGER", null)).isInstanceOf(InvalidDataException.class);
    assertThat(store.audits).isEmpty();
  }

  @Test
  void A_a_tenant_administrator_whose_subject_is_not_an_account_is_not_allowed() {
    Caller named = new Caller("some-service-name", TENANT, Set.of(Role.TENANT_ADMINISTRATOR));

    assertThatThrownBy(() -> change.execute(named, "MANAGER", true)).isInstanceOf(CallerNotAllowedException.class);
    assertThat(store.audits).isEmpty();
  }

  @Test
  void A_a_race_decides_again_once_and_a_second_race_is_reported() {
    store.races = 1;
    assertThat(change.execute(administrator, "MANAGER", true).enabled()).isTrue();

    store.races = 2;
    assertThatThrownBy(() -> change.execute(administrator, "ANALYST", true)).isInstanceOf(ConcurrentChangeException.class);
    assertThat(store.audits).hasSize(1);
  }
}
