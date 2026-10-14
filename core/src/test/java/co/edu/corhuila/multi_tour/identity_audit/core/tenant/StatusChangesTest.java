package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusChangesTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123456Z");
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final String UNKNOWN = "00000000-0000-4000-8000-0000000000ff";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private int sequence;
  private final IdGenerator ids = () -> "00000000-0000-4000-8000-%012d".formatted(++sequence);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final InactivateTenant inactivate = new InactivateTenant(tenants, ids, clock);
  private final ReactivateTenant reactivate = new ReactivateTenant(tenants, ids, clock);
  private Tenant active;

  @BeforeEach
  void anActiveTenant() {
    Tenant pending = new RegisterPendingTenant(tenants, ids, clock)
        .execute(new RegisterPendingTenant.Command("acme-tours", "Acme Tours", "America/Bogota", "CO", "key-0000001"))
        .tenant();
    active = new CompleteOnboarding(tenants, ids, clock)
        .execute(new CompleteOnboarding.Command(pending.tenantId(), MEMBERSHIP, TenantStatus.ACTIVE, "onboard-tenant", "corr-0"));
    tenants.events.clear();
  }

  private InactivateTenant.Command stop(String tenantId, String reason) {
    return new InactivateTenant.Command(tenantId, "platform-admin", reason, "corr-1");
  }

  private ReactivateTenant.Command resume(String tenantId, String reason) {
    return new ReactivateTenant.Command(tenantId, "platform-admin", reason, "corr-2");
  }

  @Test
  void A_inactivate_records_who_when_why_and_leaves_the_event_with_the_new_state() {
    var off = inactivate.execute(stop(active.tenantId(), " unpaid "));
    assertThat(off.status()).isEqualTo(TenantStatus.INACTIVE);
    assertThat(off.statusChangedBy()).isEqualTo("platform-admin");
    assertThat(off.statusReason()).isEqualTo("unpaid");
    assertThat(tenants.findById(active.tenantId())).contains(off);
    assertThat(tenants.events).singleElement().satisfies(event -> {
      assertThat(event.type().eventType()).isEqualTo("TenantInactivated");
      assertThat(event.tenant()).isEqualTo(off);
      assertThat(event.actor()).isEqualTo("platform-admin");
      assertThat(event.correlationId()).isEqualTo("corr-1");
    });
  }

  @Test
  void A_reactivate_is_explicit_and_leaves_its_own_event() {
    inactivate.execute(stop(active.tenantId(), "unpaid"));
    tenants.events.clear();
    var on = reactivate.execute(resume(active.tenantId(), "paid"));
    assertThat(on.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(on.statusReason()).isEqualTo("paid");
    assertThat(tenants.events).singleElement().satisfies(event -> {
      assertThat(event.type().eventType()).isEqualTo("TenantReactivated");
      assertThat(event.correlationId()).isEqualTo("corr-2");
    });
  }

  @Test
  void A_a_repeat_or_a_wrong_origin_is_refused_and_leaves_the_first_reason_and_no_event() {
    var off = inactivate.execute(stop(active.tenantId(), "unpaid"));
    tenants.events.clear();
    assertThatThrownBy(() -> inactivate.execute(stop(active.tenantId(), "again")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.findById(active.tenantId())).contains(off);
    assertThat(tenants.events).isEmpty();
    reactivate.execute(resume(active.tenantId(), "paid"));
    tenants.events.clear();
    assertThatThrownBy(() -> reactivate.execute(resume(active.tenantId(), "again")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_a_pending_or_failed_tenant_is_neither_inactivated_nor_reactivated() {
    var pending = new RegisterPendingTenant(tenants, ids, clock)
        .execute(new RegisterPendingTenant.Command("other-one", "Other", "America/Bogota", "CO", "key-0000002")).tenant();
    assertThatThrownBy(() -> inactivate.execute(stop(pending.tenantId(), "why")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(() -> reactivate.execute(resume(pending.tenantId(), "why")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.findById(pending.tenantId())).contains(pending);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_the_data_is_checked_before_the_tenant_is_looked_for() {
    for (String reason : new String[] {null, " ", "r".repeat(501), "a" + (char) 10 + "b"}) {
      assertThatThrownBy(() -> inactivate.execute(stop(UNKNOWN, reason))).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> reactivate.execute(resume(UNKNOWN, reason))).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> inactivate.execute(stop("not-a-uuid", "why"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inactivate.execute(new InactivateTenant.Command(UNKNOWN, " ", "why", "corr-1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inactivate.execute(stop(UNKNOWN, "why"))).isInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> reactivate.execute(resume(UNKNOWN, "why"))).isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void A_two_inactivations_at_once_leave_the_reason_of_the_first_and_refuse_the_second() {
    var twin = new InactivateTenant(tenants, ids, clock);
    tenants.beforeNextWrite = () -> twin.execute(stop(active.tenantId(), "first reason"));
    assertThatThrownBy(() -> inactivate.execute(stop(active.tenantId(), "second reason")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.findById(active.tenantId()).orElseThrow().statusReason()).isEqualTo("first reason");
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_the_actor_is_checked_like_the_reason_and_is_the_same_value_in_the_tenant_and_the_event() {
    assertThatThrownBy(() -> inactivate.execute(new InactivateTenant.Command(UNKNOWN, "a" + (char) 10 + "b", "why", "corr-1")))
        .isInstanceOf(IllegalArgumentException.class);
    var off = inactivate.execute(new InactivateTenant.Command(active.tenantId(), " admin ", "why", "corr-1"));
    assertThat(off.statusChangedBy()).isEqualTo("admin");
    assertThat(tenants.events).singleElement().satisfies(event -> assertThat(event.actor()).isEqualTo("admin"));
  }

  @Test
  void A_two_reactivations_at_once_leave_the_reason_of_the_first_and_refuse_the_second() {
    inactivate.execute(stop(active.tenantId(), "unpaid"));
    tenants.events.clear();
    var twin = new ReactivateTenant(tenants, ids, clock);
    tenants.beforeNextWrite = () -> twin.execute(resume(active.tenantId(), "first reason"));
    assertThatThrownBy(() -> reactivate.execute(resume(active.tenantId(), "second reason")))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.findById(active.tenantId()).orElseThrow().statusReason()).isEqualTo("first reason");
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_a_second_race_in_a_row_is_not_retried_again() {
    // another request changes the tenant, leaving it valid for this change, each time before the write
    Runnable second = () -> tenants.tenants.put(active.tenantId(),
        active.inactivate("a", "b", NOW.plusSeconds(3)).reactivate("a", "c", NOW.plusSeconds(4)));
    tenants.beforeNextWrite = () -> {
      tenants.tenants.put(active.tenantId(),
          active.inactivate("a", "b", NOW.plusSeconds(1)).reactivate("a", "c", NOW.plusSeconds(2)));
      tenants.beforeNextWrite = second;
    };
    assertThatThrownBy(() -> inactivate.execute(stop(active.tenantId(), "why")))
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(tenants.events).isEmpty();
  }
}
