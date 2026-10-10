package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TenantTest {

  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-10-10T11:00:00Z");

  private static Tenant pending() {
    return Tenant.registerPending(ID, "acme-tours", "Acme Tours", "America/Bogota", "CO", T0);
  }

  private static Tenant active() {
    return pending().completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0);
  }

  private static Tenant inactive() {
    return active().inactivate("platform-admin", "unpaid", T1);
  }

  private static void refused(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_a_tenant_is_born_pending_with_its_data() {
    Tenant tenant = pending();
    assertThat(tenant.status()).isEqualTo(TenantStatus.PENDING);
    assertThat(tenant.tenantId()).isEqualTo(ID);
    assertThat(tenant.commercialName()).isEqualTo("Acme Tours");
    assertThat(tenant.timeZone() + tenant.country()).isEqualTo("America/BogotaCO");
    assertThat(tenant.firstAdministratorMembershipId()).isNull();
  }

  @Test
  void D_pending_becomes_the_initial_status_chosen_at_registration() {
    for (TenantStatus initial : new TenantStatus[] {TenantStatus.ACTIVE, TenantStatus.INACTIVE}) {
      Tenant done = pending().completeOnboarding(MEMBERSHIP, initial, T1);
      assertThat(done.status()).isEqualTo(initial);
      assertThat(done.firstAdministratorMembershipId()).isEqualTo(MEMBERSHIP);
      assertThat(done.updatedAt()).isEqualTo(T1);
    }
  }

  @Test
  void D_the_initial_status_is_active_or_inactive_and_the_administrator_is_a_uuid() {
    assertThatThrownBy(() -> pending().completeOnboarding(MEMBERSHIP, TenantStatus.PENDING, T1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pending().completeOnboarding(MEMBERSHIP, TenantStatus.FAILED, T1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pending().completeOnboarding("not-a-uuid", TenantStatus.ACTIVE, T1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_pending_becomes_failed_with_the_failed_step() {
    Tenant failed = pending().failOnboarding(FailedStep.CREATE_ACCOUNT, T1);
    assertThat(failed.status()).isEqualTo(TenantStatus.FAILED);
    assertThat(failed.failedStep()).isEqualTo(FailedStep.CREATE_ACCOUNT);
  }

  @Test
  void D_inactivate_records_who_when_why() {
    Tenant off = active().inactivate("platform-admin", "unpaid", T1);
    assertThat(off.status()).isEqualTo(TenantStatus.INACTIVE);
    assertThat(off.statusChangedBy()).isEqualTo("platform-admin");
    assertThat(off.statusChangedAt()).isEqualTo(T1);
    assertThat(off.statusReason()).isEqualTo("unpaid");
  }

  @Test
  void D_reactivate_records_who_when_why() {
    Tenant on = inactive().reactivate("platform-admin", "paid", Instant.parse("2026-10-11T00:00:00Z"));
    assertThat(on.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(on.statusChangedBy()).isEqualTo("platform-admin");
    assertThat(on.statusChangedAt()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    assertThat(on.statusReason()).isEqualTo("paid");
  }

  @Test
  void D_inactivate_twice_and_reactivate_an_active_tenant_are_refused() {
    refused(() -> inactive().inactivate("platform-admin", "again", T1));
    refused(() -> active().reactivate("platform-admin", "why", T1));
  }

  @Test
  void D_no_other_action_leaves_inactive() {
    refused(() -> inactive().completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T1));
    refused(() -> inactive().failOnboarding(FailedStep.APPLY_STATUS, T1));
    assertThat(inactive().reactivate("platform-admin", "paid", T1).status()).isEqualTo(TenantStatus.ACTIVE);
  }

  @Test
  void D_only_a_pending_tenant_completes_or_fails_its_onboarding() {
    refused(() -> active().completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T1));
    refused(() -> active().failOnboarding(FailedStep.APPLY_STATUS, T1));
    refused(() -> pending().inactivate("platform-admin", "why", T1));
    refused(() -> pending().reactivate("platform-admin", "why", T1));
  }

  @Test
  void D_a_failed_tenant_is_final() {
    Tenant failed = pending().failOnboarding(FailedStep.CREATE_ACCOUNT, T1);
    refused(() -> failed.completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T1));
    refused(() -> failed.failOnboarding(FailedStep.CREATE_ACCOUNT, T1));
    refused(() -> failed.inactivate("platform-admin", "why", T1));
    refused(() -> failed.reactivate("platform-admin", "why", T1));
  }

  @Test
  void D_a_change_asks_for_who_and_why() {
    for (String blank : new String[] {null, "", "   "}) {
      assertThatThrownBy(() -> active().inactivate("platform-admin", blank, T1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> active().inactivate(blank, "why", T1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> inactive().reactivate("platform-admin", blank, T1))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> active().inactivate("platform-admin", "r".repeat(501), T1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> active().inactivate("p".repeat(101), "why", T1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_a_refused_change_leaves_the_tenant_as_it_was() {
    Tenant on = active();
    refused(() -> on.reactivate("platform-admin", "why", T1));
    assertThat(on.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(on.inactivate("platform-admin", "unpaid", T1)).isNotSameAs(on);
    assertThat(on.status()).isEqualTo(TenantStatus.ACTIVE);
  }

  @Test
  void D_an_identifier_is_lowercase_letters_digits_and_single_hyphens_of_3_to_40_characters() {
    for (String good : new String[] {"abc", "acme-tours", "a1-b2-c3", "x".repeat(40)}) {
      assertThat(Tenant.registerPending(ID, good, "Acme", "America/Bogota", "CO", T0).identifier()).isEqualTo(good);
    }
    for (String bad : new String[] {"ab", "x".repeat(41), "Acme", "acme_tours", "-acme", "acme-", "a--b", "acme tours", "acme\n", null, ""}) {
      assertThatThrownBy(() -> Tenant.registerPending(ID, bad, "Acme", "America/Bogota", "CO", T0))
          .as("identifier %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void D_the_other_data_of_a_new_tenant_is_checked() {
    assertThatThrownBy(() -> Tenant.registerPending("abc", "acme", "Acme", "America/Bogota", "CO", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "   ", "America/Bogota", "CO", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "N".repeat(121), "America/Bogota", "CO", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "Acme", "Mars/Olympus", "CO", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "Acme", "America/Bogota", "COL", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "Acme", "America/Bogota", "co", T0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_texts_are_trimmed_and_the_limits_are_accepted() {
    Tenant off = active().inactivate(" platform-admin ", " " + "r".repeat(500) + " ", T1);
    assertThat(off.statusChangedBy()).isEqualTo("platform-admin");
    assertThat(off.statusReason()).hasSize(500).doesNotStartWith(" ");
    assertThat(active().inactivate("p".repeat(100), "why", T1).statusChangedBy()).hasSize(100);
    Tenant named = Tenant.registerPending(ID, "acme", " " + "N".repeat(120) + " ", "America/Bogota", "CO", T0);
    assertThat(named.commercialName()).hasSize(120);
  }

  @Test
  void D_a_bad_argument_is_a_validation_error_even_when_the_status_would_refuse_the_change() {
    Tenant failed = pending().failOnboarding(FailedStep.CREATE_ACCOUNT, T1);
    assertThatThrownBy(() -> failed.inactivate("", "why", T1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inactive().inactivate("platform-admin", "a\nb", T1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> active().failOnboarding(null, T1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_each_status_carries_exactly_its_fields() {
    Tenant failed = pending().failOnboarding(FailedStep.APPLY_STATUS, T1);
    assertThat(failed.firstAdministratorMembershipId()).isNull();
    assertThat(failed.statusChangedAt()).isEqualTo(failed.updatedAt()).isEqualTo(T1);
    assertThat(active().failedStep()).isNull();
    assertThat(active().statusChangedBy()).isNull();
    assertThat(active().statusReason()).isNull();
    assertThat(inactive().firstAdministratorMembershipId()).isEqualTo(MEMBERSHIP);
  }

  @Test
  void D_the_time_is_required_and_kept_to_the_millisecond_that_the_database_keeps() {
    assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "Acme", "America/Bogota", "CO", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> active().inactivate("platform-admin", "why", null))
        .isInstanceOf(IllegalArgumentException.class);
    Instant precise = Instant.parse("2026-10-10T10:00:00.123456789Z");
    assertThat(Tenant.registerPending(ID, "acme", "Acme", "America/Bogota", "CO", precise).createdAt())
        .isEqualTo(Instant.parse("2026-10-10T10:00:00.123Z"));
  }

  @Test
  void D_the_time_zone_is_a_region_name_and_not_a_legacy_alias() {
    for (String legacy : new String[] {"EST5EDT", "GMT0", "SystemV/AST4", "UTC+5", "", null}) {
      assertThatThrownBy(() -> Tenant.registerPending(ID, "acme", "Acme", legacy, "CO", T0))
          .as("time zone %s", legacy)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(Tenant.registerPending(ID, "acme", "Acme", "UTC", "CO", T0).timeZone()).isEqualTo("UTC");
  }

  @Test
  void D_the_refusal_says_from_which_status_and_for_which_action() {
    assertThatThrownBy(() -> active().reactivate("platform-admin", "why", T1))
        .isInstanceOfSatisfying(InvalidStatusTransitionException.class, e -> {
          assertThat(e.from()).isEqualTo(TenantStatus.ACTIVE);
          assertThat(e.action()).isNotBlank();
        });
  }
}
