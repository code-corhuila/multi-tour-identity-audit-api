package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ListAuditTest {

  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String OTHER_TENANT = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String ACCOUNT = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-12-01T00:00:00Z");

  /** The reader in memory: it keeps the query it was asked, so the tests see which tenant the use case put in it. */
  private static final class FakeReader implements AuditReader {
    final List<AuditQuery> asked = new ArrayList<>();
    final Page<AuditRecord> answer = new Page<>(List.of(), 1, 20, 0);

    @Override
    public Page<AuditRecord> page(AuditQuery query) {
      asked.add(query);
      return answer;
    }
  }

  private final FakeReader reader = new FakeReader();
  private Set<String> enabledRoles = Set.of();
  private final AuditAccessSettings settings = new AuditAccessSettings() {
    @Override
    public List<AuditAccessSetting> find(String tenantId) {
      return AuditAccessSetting.OPTIONAL_ROLES.stream()
          .map(r -> enabledRoles.contains(r) ? new AuditAccessSetting(r, true, ACCOUNT, FROM) : AuditAccessSetting.neverSet(r)).toList();
    }

    @Override
    public void change(String tenantId, AuditAccessSetting expected, AuditAccessSetting next, AuditRecord audit) {
      throw new UnsupportedOperationException("a listing never changes a setting");
    }
  };
  private final ListPlatformAudit platform = new ListPlatformAudit(reader);
  private final ListTenantAudit tenant = new ListTenantAudit(reader, settings);

  private static Caller of(Role role, String tenantId) {
    return new Caller(ACCOUNT, tenantId, Set.of(role));
  }

  private static AuditQuery everything() {
    return new AuditQuery(null, null, null, null, 1, 20);
  }

  @Test
  void A_only_the_platform_administrator_lists_the_audit_across_tenants() {
    List<Caller> others = List.of(
        of(Role.TENANT_ADMINISTRATOR, TENANT), of(Role.MANAGER, TENANT), of(Role.ACCOUNTANT, TENANT), of(Role.ANALYST, TENANT),
        of(Role.OPERATIONAL_COLLABORATOR, TENANT), of(Role.FINAL_CUSTOMER, TENANT), new Caller("event-relay", null, Set.of(Role.SERVICE)));

    for (Caller other : others) {
      assertThatThrownBy(() -> platform.execute(other, everything())).as("%s", other.roles()).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(reader.asked).as("a refused caller reads nothing").isEmpty();

    platform.execute(of(Role.PLATFORM_ADMINISTRATOR, null), everything());
    assertThat(reader.asked).hasSize(1);
  }

  @Test
  void A_the_platform_administrator_may_narrow_the_audit_to_a_tenant_a_period_and_an_action() {
    AuditQuery narrowed = new AuditQuery(TENANT, FROM, TO, "TENANT_INACTIVATED", 2, 50);

    platform.execute(of(Role.PLATFORM_ADMINISTRATOR, null), narrowed);

    assertThat(reader.asked).containsExactly(narrowed);
  }

  @Test
  void A_a_query_refuses_a_page_a_limit_an_action_or_a_period_that_makes_no_sense() {
    assertThatThrownBy(() -> new AuditQuery(null, null, null, null, 0, 20)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditQuery(null, null, null, null, 1, 0)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditQuery(null, null, null, null, 1, 101)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new AuditQuery(null, null, null, "SOMETHING_ELSE", 1, 20)).isInstanceOf(InvalidDataException.class)
        .extracting(e -> ((InvalidDataException) e).field()).isEqualTo("action");
    assertThatThrownBy(() -> new AuditQuery(null, TO, FROM, null, 1, 20)).isInstanceOf(InvalidDataException.class)
        .extracting(e -> ((InvalidDataException) e).field()).isEqualTo("to");
    assertThatThrownBy(() -> new AuditQuery("not-a-uuid", null, null, null, 1, 20)).isInstanceOf(InvalidDataException.class);

    assertThat(new AuditQuery(null, FROM, FROM, null, 1, 100)).as("an empty period is a period, and the limit may be 100").isNotNull();
    assertThat(new AuditQuery(null, null, null, "AUDIT_ACCESS_ENABLED", 1, 1)).isNotNull();
  }

  @Test
  void A_a_tenant_audit_is_read_by_its_administrator_and_by_an_enabled_role_only() {
    tenant.execute(of(Role.TENANT_ADMINISTRATOR, TENANT), TENANT, everything());
    assertThat(reader.asked).as("the administrator needs no setting").hasSize(1);

    enabledRoles = Set.of("ANALYST");
    tenant.execute(of(Role.ANALYST, TENANT), TENANT, everything());
    assertThat(reader.asked).hasSize(2);

    for (Role without : List.of(Role.MANAGER, Role.ACCOUNTANT)) {
      assertThatThrownBy(() -> tenant.execute(of(without, TENANT), TENANT, everything())).as("%s", without)
          .isInstanceOf(CallerNotAllowedException.class);
    }
    for (Role never : List.of(Role.OPERATIONAL_COLLABORATOR, Role.FINAL_CUSTOMER)) {
      assertThatThrownBy(() -> tenant.execute(of(never, TENANT), TENANT, everything())).as("%s", never)
          .isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(reader.asked).as("a refused role reads nothing").hasSize(2);
  }

  @Test
  void A_a_caller_with_two_optional_roles_reads_when_any_of_them_has_the_access() {
    Caller both = new Caller(ACCOUNT, TENANT, Set.of(Role.MANAGER, Role.ANALYST));
    enabledRoles = Set.of("ANALYST");

    tenant.execute(both, TENANT, everything());
    assertThat(reader.asked).hasSize(1);

    enabledRoles = Set.of();
    assertThatThrownBy(() -> tenant.execute(both, TENANT, everything())).isInstanceOf(CallerNotAllowedException.class);
    assertThat(reader.asked).hasSize(1);
  }

  @Test
  void A_another_tenants_audit_is_404_for_every_role_and_it_is_judged_before_the_role() {
    enabledRoles = Set.of("MANAGER");
    for (Role role : List.of(Role.TENANT_ADMINISTRATOR, Role.MANAGER, Role.ANALYST, Role.OPERATIONAL_COLLABORATOR, Role.FINAL_CUSTOMER)) {
      assertThatThrownBy(() -> tenant.execute(of(role, TENANT), OTHER_TENANT, everything())).as("%s", role)
          .isInstanceOf(TenantNotFoundException.class);
    }
    assertThat(reader.asked).isEmpty();
  }

  @Test
  void A_the_platform_administrator_and_a_service_are_refused_on_the_route_of_one_tenant() {
    assertThatThrownBy(() -> tenant.execute(of(Role.PLATFORM_ADMINISTRATOR, null), TENANT, everything()))
        .isInstanceOf(CallerNotAllowedException.class);
    assertThatThrownBy(() -> tenant.execute(new Caller("event-relay", null, Set.of(Role.SERVICE)), TENANT, everything()))
        .isInstanceOf(CallerNotAllowedException.class);
    assertThat(reader.asked).isEmpty();
  }

  @Test
  void A_the_tenant_audit_is_always_narrowed_to_the_tenant_of_the_caller_whatever_the_query_says() {
    tenant.execute(of(Role.TENANT_ADMINISTRATOR, TENANT), TENANT, new AuditQuery(OTHER_TENANT, FROM, TO, "ACCOUNT_BLOCKED", 3, 10));

    assertThat(reader.asked).containsExactly(new AuditQuery(TENANT, FROM, TO, "ACCOUNT_BLOCKED", 3, 10));
  }
}
