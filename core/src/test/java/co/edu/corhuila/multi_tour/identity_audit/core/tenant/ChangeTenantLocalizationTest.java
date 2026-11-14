package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Spec 010 (HU-IDA-004): the zone and the country change forward only, and never while a cash box is open. */
class ChangeTenantLocalizationTest {

  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String ACCOUNT = "00000000-0000-4000-8000-0000000000cc";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final FakeCashBoxes cash = new FakeCashBoxes();
  private final ChangeTenantLocalization change =
      new ChangeTenantLocalization(tenants, cash, () -> "11111111-1111-4111-8111-111111111111", Clock.fixed(T1, ZoneOffset.UTC));

  /** The cash boxes of each tenant that are open or pending closing, and whether the read fails. */
  static final class FakeCashBoxes implements CashBoxes {
    final java.util.Set<String> open = new java.util.HashSet<>();
    boolean unreadable;
    int reads;

    @Override
    public boolean anyOpenOrPendingClosing(String tenantId) {
      reads++;
      if (unreadable) {
        throw new CashBoxesUnavailableException();
      }
      return open.contains(tenantId);
    }
  }

  @BeforeEach
  void twoActiveTenants() {
    for (String id : new String[] {A, B}) {
      tenants.tenants.put(id, Tenant.registerPending(id, "tenant-" + id.substring(35), "Tenant", "America/Bogota", "CO", T0)
          .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
    }
  }

  private static Caller administratorOf(String tenantId) {
    return new Caller(ACCOUNT, tenantId, Set.of(Role.TENANT_ADMINISTRATOR));
  }

  private Tenant run(Caller caller, String zone, String country) {
    return change.execute(new ChangeTenantLocalization.Command(caller, zone, country, "trace-1"));
  }

  @Test
  void A_the_change_writes_the_effective_instant_and_nothing_else() {
    Tenant changed = run(administratorOf(A), "America/Mexico_City", "MX");

    assertThat(changed.timeZone()).isEqualTo("America/Mexico_City");
    assertThat(changed.country()).isEqualTo("MX");
    assertThat(changed.updatedAt()).isEqualTo(T1);
    assertThat(changed.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(tenants.tenants.get(B).timeZone()).isEqualTo("America/Bogota");
    assertThat(tenants.events).hasSize(1);
    TenantEvent event = tenants.events.get(0);
    assertThat(event.type()).isEqualTo(TenantEventType.TENANT_LOCALIZATION_CHANGED);
    assertThat(event.type().eventType()).isEqualTo("TenantLocalizationChanged");
    assertThat(event.occurredAt()).isEqualTo(T1);
    assertThat(event.actor()).isEqualTo(ACCOUNT);
    assertThat(event.previous().timeZone()).isEqualTo("America/Bogota");
    assertThat(event.previous().country()).isEqualTo("CO");
  }

  @Test
  void A_the_audit_records_the_localization_change() {
    run(administratorOf(A), "America/Mexico_City", "MX");

    var record = tenants.events.get(0).toAuditRecord("22222222-2222-4222-8222-222222222222", tenants.events.get(0).previous());
    assertThat(record.action()).isEqualTo("TENANT_LOCALIZATION_CHANGED");
    assertThat(record.affectedRecordId()).isEqualTo("tenants/" + A);
    assertThat(record.actorId()).isEqualTo(ACCOUNT);
    assertThat(record.previousValue()).containsEntry("timeZone", "America/Bogota").containsEntry("country", "CO");
    assertThat(record.newValue()).containsEntry("timeZone", "America/Mexico_City").containsEntry("country", "MX");
  }

  @Test
  void A_the_same_value_changes_nothing() {
    Tenant same = run(administratorOf(A), "America/Bogota", "CO");

    assertThat(same.updatedAt()).isEqualTo(T0);
    assertThat(tenants.events).isEmpty();
    assertThat(cash.reads).isZero();
  }

  @Test
  void A_an_open_cash_blocks_the_zone_change() {
    cash.open.add(A);

    assertThatThrownBy(() -> run(administratorOf(A), "America/Mexico_City", null)).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(tenants.tenants.get(A).timeZone()).isEqualTo("America/Bogota");
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_a_pending_closing_cash_blocks_the_zone_change_too_and_only_that_tenant_is_looked_at() {
    // the fake does not tell open from pending closing: both are "open or pending closing" for the port
    cash.open.add(B);

    assertThat(run(administratorOf(A), "America/Mexico_City", null).timeZone()).isEqualTo("America/Mexico_City");
    assertThatThrownBy(() -> run(administratorOf(B), "America/Lima", null)).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_the_country_alone_is_not_blocked_by_cash() {
    cash.open.add(A);

    assertThat(run(administratorOf(A), null, "MX").country()).isEqualTo("MX");
    assertThat(cash.reads).isZero();
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_an_unreadable_cash_state_changes_nothing_and_is_not_swallowed() {
    cash.unreadable = true;

    assertThatThrownBy(() -> run(administratorOf(A), "America/Mexico_City", "MX")).isInstanceOf(CashBoxesUnavailableException.class);
    assertThat(tenants.tenants.get(A).timeZone()).isEqualTo("America/Bogota");
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_an_inactive_tenant_is_refused() {
    tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform", "reason", T0));

    assertThatThrownBy(() -> run(administratorOf(A), null, "MX")).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_an_inactive_tenant_is_refused_even_with_the_value_it_already_has() {
    tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform", "reason", T0));

    assertThatThrownBy(() -> run(administratorOf(A), "America/Bogota", "CO")).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_a_stored_value_that_is_no_longer_accepted_does_not_refuse_a_change_of_the_other_field() {
    tenants.tenants.put(A, Tenant.registerPending(A, "tenant-a", "Tenant", "America/Bogota", "ZZ", T0).completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));

    assertThat(run(administratorOf(A), "America/Lima", null).timeZone()).isEqualTo("America/Lima");
  }

  @Test
  void D_the_zone_must_exist_and_the_country_must_be_a_known_code_and_one_of_them_is_needed() {
    assertThatThrownBy(() -> run(administratorOf(A), "Mars/Olympus", null))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("timeZone"));
    assertThatThrownBy(() -> run(administratorOf(A), null, "ZZ"))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("country"));
    assertThatThrownBy(() -> run(administratorOf(A), null, "co"))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("country"));
    assertThatThrownBy(() -> run(administratorOf(A), null, null)).isInstanceOf(InvalidDataException.class);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void C_only_the_tenant_administrator_changes_the_localization() {
    Caller[] refused = {
        new Caller(ACCOUNT, null, Set.of(Role.PLATFORM_ADMINISTRATOR)),
        new Caller("onboard-tenant", null, Set.of(Role.SERVICE)),
        new Caller(ACCOUNT, A, Set.of(Role.MANAGER)),
        new Caller(ACCOUNT, A, Set.of(Role.FINAL_CUSTOMER))};
    for (Caller caller : refused) {
      assertThatThrownBy(() -> run(caller, null, "MX")).as("%s", caller).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(tenants.events).isEmpty();
  }
}
