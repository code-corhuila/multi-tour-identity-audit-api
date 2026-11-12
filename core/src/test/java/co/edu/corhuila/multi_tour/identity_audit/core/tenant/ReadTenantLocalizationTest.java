package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Spec 010, rule RN-IDA-API-107 (HU-IDA-004): the Tenant Administrator reads the zone and the country of their own tenant only. */
class ReadTenantLocalizationTest {

  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String ACCOUNT = "00000000-0000-4000-8000-0000000000cc";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final ReadTenantLocalization read = new ReadTenantLocalization(tenants);

  @BeforeEach
  void twoTenantsWithTheirOwnLocalization() {
    tenants.tenants.put(A, Tenant.registerPending(A, "tenant-a", "Tenant", "America/Bogota", "CO", T0)
        .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
    tenants.tenants.put(B, Tenant.registerPending(B, "tenant-b", "Tenant", "America/Mexico_City", "MX", T0)
        .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
  }

  private static Caller of(String tenantId, Role role) {
    return new Caller(ACCOUNT, tenantId, Set.of(role));
  }

  @Test
  void A_the_administrator_reads_the_localization_of_the_tenant_of_the_token_only() {
    Tenant mine = read.execute(of(A, Role.TENANT_ADMINISTRATOR));

    assertThat(mine.tenantId()).isEqualTo(A);
    assertThat(mine.timeZone()).isEqualTo("America/Bogota");
    assertThat(mine.country()).isEqualTo("CO");
    assertThat(read.execute(of(B, Role.TENANT_ADMINISTRATOR)).country()).isEqualTo("MX");
  }

  @Test
  void B_an_inactive_tenant_is_read_all_the_same() {
    tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform-admin", "unpaid", T0));

    assertThat(read.execute(of(A, Role.TENANT_ADMINISTRATOR)).status()).isEqualTo(TenantStatus.INACTIVE);
  }

  @Test
  void C_anyone_but_the_administrator_of_a_tenant_is_refused() {
    for (Role other : new Role[] {Role.MANAGER, Role.OPERATIONAL_COLLABORATOR, Role.FINAL_CUSTOMER}) {
      assertThatThrownBy(() -> read.execute(of(A, other))).isInstanceOf(CallerNotAllowedException.class);
    }
    // the Platform Administrator and a service have no tenant
    for (Role other : new Role[] {Role.PLATFORM_ADMINISTRATOR, Role.SERVICE}) {
      assertThatThrownBy(() -> read.execute(of(null, other))).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThatThrownBy(() -> read.execute(of(null, Role.TENANT_ADMINISTRATOR))).isInstanceOf(CallerNotAllowedException.class);
  }

  @Test
  void D_a_tenant_that_does_not_exist_is_not_found() {
    tenants.tenants.clear();

    assertThatThrownBy(() -> read.execute(of(A, Role.TENANT_ADMINISTRATOR))).isInstanceOf(TenantNotFoundException.class);
  }
}
