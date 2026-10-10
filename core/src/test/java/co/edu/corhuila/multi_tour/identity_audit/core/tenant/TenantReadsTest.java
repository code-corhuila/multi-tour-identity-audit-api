package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantReadsTest {

  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final ListTenants list = new ListTenants(tenants);
  private final ConsultTenant consult = new ConsultTenant(tenants);
  private final GetPublicProfile profile = new GetPublicProfile(tenants);

  private static String id(int n) {
    return "00000000-0000-4000-8000-%012d".formatted(n);
  }

  /** Tenant n: created n minutes after T0; 1 and 4 are inactive, 5 is failed, 3 stays pending, the rest active. */
  @BeforeEach
  void fiveTenants() {
    for (int n = 1; n <= 5; n++) {
      Instant at = T0.plusSeconds(60L * n);
      Tenant tenant = Tenant.registerPending(id(n), "tenant-" + n, "Tenant " + n, "America/Bogota", "CO", at);
      tenant = switch (n) {
        case 3 -> tenant;
        case 5 -> tenant.failOnboarding(FailedStep.CREATE_ACCOUNT, at);
        case 1, 4 -> tenant.completeOnboarding(MEMBERSHIP, TenantStatus.INACTIVE, at);
        default -> tenant.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, at);
      };
      tenants.tenants.put(id(n), tenant);
    }
  }

  private static List<String> ids(Page<Tenant> page) {
    return page.data().stream().map(Tenant::tenantId).toList();
  }

  @Test
  void A_list_answers_the_newest_first_in_pages_with_their_total() {
    var first = list.execute(new ListTenants.Query(null, 1, 2));
    assertThat(ids(first)).containsExactly(id(5), id(4));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(ids(list.execute(new ListTenants.Query(null, 3, 2)))).containsExactly(id(1));
    assertThat(list.execute(new ListTenants.Query(null, 4, 2)).data()).isEmpty();
  }

  @Test
  void A_list_filters_by_status() {
    assertThat(ids(list.execute(new ListTenants.Query(TenantStatus.INACTIVE, 1, 20)))).containsExactly(id(4), id(1));
    assertThat(ids(list.execute(new ListTenants.Query(TenantStatus.PENDING, 1, 20)))).containsExactly(id(3));
    assertThat(list.execute(new ListTenants.Query(TenantStatus.FAILED, 1, 20)).total()).isEqualTo(1);
  }

  @Test
  void A_list_refuses_a_page_or_a_limit_out_of_range_and_does_not_trim_it() {
    for (int[] bad : new int[][] {{0, 20}, {-1, 20}, {1, 0}, {1, 101}}) {
      assertThatThrownBy(() -> list.execute(new ListTenants.Query(null, bad[0], bad[1])))
          .as("page %d limit %d", bad[0], bad[1])
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(list.execute(new ListTenants.Query(null, 1, 100)).data()).hasSize(5);
  }

  @Test
  void A_the_total_pages_round_up_and_an_empty_listing_has_none() {
    assertThat(new Page<String>(List.of(), 1, 20, 0).totalPages()).isZero();
    assertThat(new Page<String>(List.of(), 1, 20, 20).totalPages()).isEqualTo(1);
    assertThat(new Page<String>(List.of(), 1, 20, 21).totalPages()).isEqualTo(2);
  }

  @Test
  void A_consult_answers_a_tenant_of_any_status_and_refuses_what_is_not_there() {
    assertThat(consult.execute(id(5)).status()).isEqualTo(TenantStatus.FAILED);
    assertThat(consult.execute(id(3)).status()).isEqualTo(TenantStatus.PENDING);
    assertThatThrownBy(() -> consult.execute(id(99))).isInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> consult.execute("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void A_the_public_profile_shows_name_identifier_and_status_of_an_active_or_inactive_tenant() {
    assertThat(profile.execute("tenant-2")).isEqualTo(new PublicTenantProfile("Tenant 2", "tenant-2", TenantStatus.ACTIVE));
    assertThat(profile.execute("tenant-1").status()).isEqualTo(TenantStatus.INACTIVE);
  }

  @Test
  void A_the_public_profile_of_an_unknown_unfinished_or_failed_tenant_is_not_found() {
    for (String identifier : new String[] {"nobody-here", "tenant-3", "tenant-5", "Tenant_2", "", " ", null}) {
      assertThatThrownBy(() -> profile.execute(identifier)).as("%s", identifier).isInstanceOf(TenantNotFoundException.class);
    }
  }

  @Test
  void A_the_public_profile_reveals_nothing_beyond_name_identifier_and_status() {
    assertThat(PublicTenantProfile.class.getRecordComponents()).extracting("name")
        .containsExactly("commercialName", "identifier", "status");
  }
}
