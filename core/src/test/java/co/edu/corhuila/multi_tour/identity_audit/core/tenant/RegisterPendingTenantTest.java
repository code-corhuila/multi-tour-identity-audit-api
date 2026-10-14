package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RegisterPendingTenantTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123456Z");

  private final InMemoryTenants tenants = new InMemoryTenants();
  private int sequence;
  private final IdGenerator ids = () -> "00000000-0000-4000-8000-%012d".formatted(++sequence);
  private final RegisterPendingTenant register =
      new RegisterPendingTenant(tenants, ids, Clock.fixed(NOW, ZoneOffset.UTC));

  private static RegisterPendingTenant.Command command(String identifier, String key) {
    return new RegisterPendingTenant.Command(identifier, "Acme Tours", "America/Bogota", "CO", key);
  }

  private static RepeatKey key(String value) {
    return new RepeatKey(RegisterPendingTenant.OPERATION, value);
  }

  @Test
  void A_register_creates_a_pending_tenant_and_keeps_it_with_its_key() {
    var result = register.execute(command("acme-tours", "key-0000001"));
    assertThat(result.created()).isTrue();
    assertThat(result.tenant().status()).isEqualTo(TenantStatus.PENDING);
    assertThat(result.tenant().createdAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00.123Z"));
    assertThat(tenants.findByRepeatKey(key("key-0000001"))).contains(result.tenant());
    assertThat(tenants.findByIdentifier("acme-tours")).contains(result.tenant());
  }

  @Test
  void A_register_repeated_answers_the_first_tenant_without_looking_at_the_body() {
    var first = register.execute(command("acme-tours", "key-0000001"));
    var again = register.execute(command("Not_Even_Valid", "key-0000001"));
    assertThat(again).isEqualTo(new RegisterPendingTenant.Result(first.tenant(), false));
    assertThat(tenants.tenants).hasSize(1);
  }

  @Test
  void A_register_repeated_after_the_tenant_changed_answers_it_as_it_is_now() {
    var first = register.execute(command("acme-tours", "key-0000001")).tenant();
    var failed = first.failOnboarding(FailedStep.CREATE_ACCOUNT, NOW);
    tenants.tenants.put(first.tenantId(), failed);
    assertThat(register.execute(command("acme-tours", "key-0000001")).tenant()).isEqualTo(failed);
  }

  @Test
  void A_register_with_a_live_identifier_names_it() {
    register.execute(command("acme-tours", "key-0000001"));
    assertThatThrownBy(() -> register.execute(command("acme-tours", "key-0000002")))
        .isInstanceOfSatisfying(
            IdentifierTakenException.class, e -> assertThat(e.identifier()).isEqualTo("acme-tours"));
    assertThat(tenants.keys).doesNotContainKey(key("key-0000002"));
  }

  @Test
  void A_a_failed_tenant_frees_the_identifier() {
    var first = register.execute(command("acme-tours", "key-0000001")).tenant();
    tenants.tenants.put(first.tenantId(), first.failOnboarding(FailedStep.CREATE_ACCOUNT, NOW));
    var second = register.execute(command("acme-tours", "key-0000002"));
    assertThat(second.tenant().tenantId()).isNotEqualTo(first.tenantId());
    assertThat(tenants.findByIdentifier("acme-tours")).contains(second.tenant());
  }

  @Test
  void A_register_checks_the_data_and_the_key_before_looking_for_the_identifier() {
    register.execute(command("acme-tours", "key-0000001"));
    assertThatThrownBy(() -> register.execute(command("Acme_Tours", "key-0000002")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> register.execute(command("acme-tours", "short")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> register.execute(command("acme-tours", "has a space")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void A_register_that_loses_the_race_for_the_identifier_is_refused_and_stores_nothing() {
    var other = new RegisterPendingTenant(tenants, ids, Clock.fixed(NOW, ZoneOffset.UTC));
    tenants.beforeNextWrite = () -> other.execute(command("acme-tours", "key-0000009"));
    assertThatThrownBy(() -> register.execute(command("acme-tours", "key-0000001")))
        .isInstanceOf(IdentifierTakenException.class);
    assertThat(tenants.tenants).hasSize(1);
    assertThat(tenants.keys).doesNotContainKey(key("key-0000001"));
  }

  @Test
  void A_register_that_loses_the_race_for_its_own_key_answers_the_winner_even_if_the_identifier_also_conflicts() {
    var other = new RegisterPendingTenant(tenants, ids, Clock.fixed(NOW, ZoneOffset.UTC));
    tenants.beforeNextWrite = () -> other.execute(command("acme-tours", "key-0000001"));
    var loser = register.execute(command("acme-tours", "key-0000001"));
    assertThat(loser.created()).isFalse();
    assertThat(loser.tenant()).isEqualTo(tenants.findByRepeatKey(key("key-0000001")).orElseThrow());
    assertThat(tenants.tenants).hasSize(1);
  }

  @Test
  void A_register_whose_first_send_won_is_answered_with_it_whichever_conflict_the_repository_finds_first() {
    var other = new RegisterPendingTenant(tenants, ids, Clock.fixed(NOW, ZoneOffset.UTC));
    tenants.identifierConflictFirst = true;
    tenants.beforeNextWrite = () -> other.execute(command("acme-tours", "key-0000001"));
    var loser = register.execute(command("acme-tours", "key-0000001"));
    assertThat(loser.created()).isFalse();
    assertThat(tenants.tenants).hasSize(1);
  }
}
