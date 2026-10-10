package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingStepsTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123456Z");
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final String UNKNOWN = "00000000-0000-4000-8000-0000000000ff";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private int sequence;
  private final IdGenerator ids = () -> "00000000-0000-4000-8000-%012d".formatted(++sequence);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private CompleteOnboarding complete;
  private FailOnboarding fail;
  private Tenant pending;

  @BeforeEach
  void aPendingTenant() {
    complete = new CompleteOnboarding(tenants, ids, clock);
    fail = new FailOnboarding(tenants, clock);
    pending = new RegisterPendingTenant(tenants, ids, clock)
        .execute(new RegisterPendingTenant.Command("acme-tours", "Acme Tours", "America/Bogota", "CO", "key-0000001"))
        .tenant();
  }

  private CompleteOnboarding.Command completion(String tenantId, TenantStatus initial) {
    return new CompleteOnboarding.Command(tenantId, MEMBERSHIP, initial, "onboard-tenant", "corr-1");
  }

  private FailOnboarding.Command failure(String tenantId, FailedStep step) {
    return new FailOnboarding.Command(tenantId, step);
  }

  @Test
  void A_complete_applies_the_initial_status_and_leaves_one_event_with_the_new_state() {
    var done = complete.execute(completion(pending.tenantId(), TenantStatus.INACTIVE));
    assertThat(done.status()).isEqualTo(TenantStatus.INACTIVE);
    assertThat(tenants.findById(pending.tenantId())).contains(done);
    assertThat(tenants.events).singleElement().satisfies(event -> {
      assertThat(event.type().eventType()).isEqualTo("TenantCreated");
      assertThat(event.tenant()).isEqualTo(done);
      assertThat(event.actor()).isEqualTo("onboard-tenant");
      assertThat(event.correlationId()).isEqualTo("corr-1");
      assertThat(event.occurredAt()).isEqualTo(done.updatedAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00.123Z"));
      assertThat(event.eventId()).matches("[0-9a-f-]{36}").isNotEqualTo(pending.tenantId());
    });
  }

  @Test
  void A_complete_repeated_with_the_same_data_answers_the_tenant_and_leaves_no_second_event() {
    var done = complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE));
    assertThat(complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE))).isEqualTo(done);
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_complete_repeated_after_the_tenant_changed_again_still_answers_it_and_leaves_no_event() {
    var done = complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE));
    var inactive = done.inactivate("platform-admin", "unpaid", NOW.plusSeconds(60));
    tenants.tenants.put(done.tenantId(), inactive);
    assertThat(complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE))).isEqualTo(inactive);
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_complete_with_other_data_on_a_tenant_that_is_not_pending_is_refused() {
    complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE));
    var other = new CompleteOnboarding.Command(pending.tenantId(), "00000000-0000-4000-8000-0000000000bb",
        TenantStatus.ACTIVE, "onboard-tenant", "corr-1");
    assertThatThrownBy(() -> complete.execute(other)).isInstanceOf(InvalidStatusTransitionException.class);
    var failed = new RegisterPendingTenant(tenants, ids, clock)
        .execute(new RegisterPendingTenant.Command("other-one", "Other", "America/Bogota", "CO", "key-0000002")).tenant();
    fail.execute(failure(failed.tenantId(), FailedStep.CREATE_ACCOUNT));
    assertThatThrownBy(() -> complete.execute(completion(failed.tenantId(), TenantStatus.ACTIVE)))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.events).hasSize(1);
  }

  private void refusesAsInvalid(String tenantId, String membership, TenantStatus initial, String actor, String corr) {
    assertThatThrownBy(() -> complete.execute(new CompleteOnboarding.Command(tenantId, membership, initial, actor, corr)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void A_complete_checks_its_data_before_it_looks_for_the_tenant() {
    refusesAsInvalid(UNKNOWN, "not-a-uuid", TenantStatus.ACTIVE, "onboard-tenant", "corr-1");
    refusesAsInvalid(UNKNOWN, MEMBERSHIP, TenantStatus.PENDING, "onboard-tenant", "corr-1");
    refusesAsInvalid(UNKNOWN, MEMBERSHIP, TenantStatus.ACTIVE, " ", "corr-1");
    refusesAsInvalid(UNKNOWN, MEMBERSHIP, TenantStatus.ACTIVE, "onboard-tenant", null);
    refusesAsInvalid(null, MEMBERSHIP, TenantStatus.ACTIVE, "onboard-tenant", "corr-1");
    refusesAsInvalid("not-a-uuid", MEMBERSHIP, TenantStatus.ACTIVE, "onboard-tenant", "corr-1");
    refusesAsInvalid(UNKNOWN, MEMBERSHIP, TenantStatus.ACTIVE, "a".repeat(101), "corr-1");
    refusesAsInvalid(UNKNOWN, MEMBERSHIP, TenantStatus.ACTIVE, "onboard-tenant", "c".repeat(129));
    assertThatThrownBy(() -> complete.execute(completion(UNKNOWN, TenantStatus.ACTIVE)))
        .isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void A_complete_that_races_with_the_same_step_answers_the_winner_and_leaves_one_event() {
    var twin = new CompleteOnboarding(tenants, ids, clock);
    tenants.beforeNextWrite = () -> twin.execute(completion(pending.tenantId(), TenantStatus.ACTIVE));
    var loser = complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE));
    assertThat(loser.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(tenants.events).hasSize(1);
  }

  @Test
  void A_complete_that_loses_the_race_against_a_failure_is_refused_and_leaves_no_event() {
    tenants.beforeNextWrite = () -> fail.execute(failure(pending.tenantId(), FailedStep.CREATE_ACCOUNT));
    assertThatThrownBy(() -> complete.execute(completion(pending.tenantId(), TenantStatus.ACTIVE)))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(tenants.findById(pending.tenantId()).orElseThrow().status()).isEqualTo(TenantStatus.FAILED);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_fail_marks_the_step_tells_nobody_and_can_be_repeated() {
    var failed = fail.execute(failure(pending.tenantId(), FailedStep.APPLY_STATUS));
    assertThat(failed.status()).isEqualTo(TenantStatus.FAILED);
    assertThat(failed.failedStep()).isEqualTo(FailedStep.APPLY_STATUS);
    assertThat(fail.execute(failure(pending.tenantId(), FailedStep.APPLY_STATUS))).isEqualTo(failed);
    assertThat(tenants.events).isEmpty();
  }

  @Test
  void A_fail_with_another_step_or_on_a_tenant_that_is_not_pending_is_refused() {
    fail.execute(failure(pending.tenantId(), FailedStep.APPLY_STATUS));
    assertThatThrownBy(() -> fail.execute(failure(pending.tenantId(), FailedStep.CREATE_ACCOUNT)))
        .isInstanceOf(InvalidStatusTransitionException.class);
    var active = new RegisterPendingTenant(tenants, ids, clock)
        .execute(new RegisterPendingTenant.Command("other-one", "Other", "America/Bogota", "CO", "key-0000002")).tenant();
    complete.execute(completion(active.tenantId(), TenantStatus.ACTIVE));
    assertThatThrownBy(() -> fail.execute(failure(active.tenantId(), FailedStep.APPLY_STATUS)))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void A_fail_checks_its_data_before_it_looks_for_the_tenant() {
    assertThatThrownBy(() -> fail.execute(new FailOnboarding.Command(UNKNOWN, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fail.execute(new FailOnboarding.Command("not-a-uuid", FailedStep.APPLY_STATUS)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fail.execute(failure(UNKNOWN, FailedStep.APPLY_STATUS)))
        .isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void A_an_event_needs_everything_and_its_text_form_leaves_out_what_a_person_wrote() {
    var event = new TenantEvent(ids.newId(), TenantEventType.TENANT_CREATED, pending, "onboard-tenant", "corr-1", NOW);
    assertThat(event.toString()).contains(event.eventId(), pending.tenantId()).doesNotContain("Acme Tours");
    assertThatThrownBy(() -> new TenantEvent(ids.newId(), TenantEventType.TENANT_CREATED, pending, " ", "corr-1", NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantEvent(ids.newId(), TenantEventType.TENANT_CREATED, pending, "who", null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantEvent(null, TenantEventType.TENANT_CREATED, pending, "who", "corr-1", NOW))
        .isInstanceOf(IllegalArgumentException.class);
    // what the validator of the database would refuse is a validation error here, not a failure of the write
    for (String[] bad : new String[][] {{"who", "has space"}, {"who", "tab" + (char) 9 + "x"}, {" who", "corr-1"},
        {"who ", "corr-1"}, {"w" + (char) 10 + "o", "corr-1"}}) {
      assertThatThrownBy(() -> new TenantEvent(ids.newId(), TenantEventType.TENANT_CREATED, pending, bad[0], bad[1], NOW))
          .as("%s / %s", bad[0], bad[1])
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
