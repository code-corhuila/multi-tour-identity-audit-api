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

class SetTenantVisualIdentityTest {

  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-10-11T10:00:00Z");
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final SetTenantVisualIdentity set =
      new SetTenantVisualIdentity(tenants, Clock.fixed(T1, ZoneOffset.UTC));

  @BeforeEach
  void twoActiveTenants() {
    for (String id : new String[] {A, B}) {
      tenants.tenants.put(id, Tenant.registerPending(id, "tenant-" + id.substring(35), "Tenant", "America/Bogota", "CO", T0)
          .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
    }
  }

  private static Caller administratorOf(String tenantId) {
    return new Caller("account-1", tenantId, Set.of(Role.TENANT_ADMINISTRATOR));
  }

  @Test
  void A_set_visual_identity_uses_the_callers_tenant() {
    Tenant changed = set.execute(administratorOf(A), "#aa0000", "#00BB00");
    assertThat(changed.tenantId()).isEqualTo(A);
    assertThat(changed.visualIdentity()).isEqualTo(new VisualIdentity("#AA0000", "#00BB00"));
    assertThat(changed.updatedAt()).isEqualTo(T1);
    assertThat(tenants.tenants.get(A).visualIdentity()).isEqualTo(changed.visualIdentity());
    assertThat(tenants.tenants.get(B).visualIdentity()).isNull();
  }

  @Test
  void A_any_other_role_and_a_caller_without_a_tenant_are_refused_and_nothing_is_written() {
    Caller[] refused = {
        new Caller("account-1", null, Set.of(Role.PLATFORM_ADMINISTRATOR)),
        new Caller("onboard-tenant", null, Set.of(Role.SERVICE)),
        new Caller("account-2", A, Set.of(Role.FINAL_CUSTOMER)),
        new Caller("account-3", A, Set.of(Role.MANAGER)),
        new Caller("account-4", null, Set.of(Role.TENANT_ADMINISTRATOR))};
    for (Caller caller : refused) {
      assertThatThrownBy(() -> set.execute(caller, "#AA0000", "#00BB00"))
          .as("%s", caller).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(tenants.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void A_a_bad_color_is_refused_naming_the_field_and_nothing_is_written() {
    assertThatThrownBy(() -> set.execute(administratorOf(A), "red", "#00BB00"))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("primaryColor"));
    assertThatThrownBy(() -> set.execute(administratorOf(A), "#AA0000", "#00BB00\n"))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("secondaryColor"));
    assertThat(tenants.tenants.get(A).visualIdentity()).isNull();
  }

  @Test
  void A_an_inactive_tenant_is_refused_as_a_business_rule_and_stays_as_it_was() {
    Tenant off = tenants.tenants.get(A).inactivate("platform-admin", "unpaid", T0);
    tenants.tenants.put(A, off);
    assertThatThrownBy(() -> set.execute(administratorOf(A), "#AA0000", "#00BB00"))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(tenants.tenants.get(A)).isEqualTo(off);
  }

  @Test
  void A_a_tenant_that_does_not_exist_is_not_found() {
    assertThatThrownBy(() -> set.execute(administratorOf("00000000-0000-4000-8000-0000000000ff"), "#AA0000", "#00BB00"))
        .isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void A_the_same_colors_again_leave_the_same_state() {
    Tenant first = set.execute(administratorOf(A), "#AA0000", "#00BB00");
    assertThat(set.execute(administratorOf(A), "#aa0000", "#00bb00")).isEqualTo(first);
  }

  @Test
  void A_a_tenant_inactivated_between_the_read_and_the_write_is_a_business_rule_violation() {
    tenants.beforeNextWrite = () -> tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform-admin", "x", T0));
    assertThatThrownBy(() -> set.execute(administratorOf(A), "#AA0000", "#00BB00"))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(tenants.tenants.get(A).visualIdentity()).isNull();
  }
}