package co.edu.corhuila.multi_tour.identity_audit.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.AuditRecord;
import co.edu.corhuila.multi_tour.identity_audit.core.audit.PlatformEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation.Command;
import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation.Outcome;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplySessionRevocationTest {

  private static final String EVENT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String OTHER_EVENT = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String ACCOUNT = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final Instant T10 = Instant.parse("2026-10-24T10:00:00Z");
  private static final Instant T20 = Instant.parse("2026-10-24T20:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-24T21:00:00Z");

  /** The port in memory: the same rules as the adapter, with an optional race injected before the write. */
  private static final class FakeCutoffs implements SessionCutoffs {
    final Map<String, SessionCutoff> cutoffs = new HashMap<>();
    final Set<String> processed = new HashSet<>();
    final List<AuditRecord> audits = new ArrayList<>();
    int races;

    @Override
    public Optional<SessionCutoff> find(String accountId) {
      return Optional.ofNullable(cutoffs.get(accountId));
    }

    @Override
    public boolean record(String eventId, SessionCutoff expected, SessionCutoff next, Instant now, AuditRecord audit) {
      if (races > 0) {
        races--;
        throw new ConcurrentChangeException("account session cutoff", next.accountId());
      }
      if (!processed.add(eventId)) {
        return false;
      }
      audits.add(audit);
      if (!next.equals(expected)) {
        cutoffs.put(next.accountId(), next);
      }
      return true;
    }
  }

  private final FakeCutoffs store = new FakeCutoffs();
  private final ApplySessionRevocation apply =
      new ApplySessionRevocation(store, Clock.fixed(NOW, ZoneOffset.UTC), () -> "00000000-0000-4000-8000-0000000000aa");

  private static Command command(String eventId, Instant from) {
    return new Command(eventId, "AccountSessionsRevoked", TENANT, ACCOUNT, ACCOUNT, from.toString());
  }

  private static PlatformEvent event(String eventId, Instant from) {
    return new PlatformEvent(eventId, "AccountSessionsRevoked", TENANT, ACCOUNT, null, null,
        Map.of("accountId", ACCOUNT, "revokedFrom", from.toString()), from);
  }

  private Outcome run(String eventId, Instant from) {
    return apply.execute(command(eventId, from), event(eventId, from));
  }

  @Test
  void A_a_revocation_keeps_its_audit_record_with_its_cutoff() {
    run(EVENT, T10);

    assertThat(store.audits).singleElement().satisfies(r -> {
      assertThat(r.eventId()).isEqualTo(EVENT);
      assertThat(r.action()).isEqualTo("ACCOUNT_SESSIONS_REVOKED");
      assertThat(r.affectedRecordId()).isEqualTo("accounts/" + ACCOUNT);
      assertThat(r.tenantId()).isEqualTo(TENANT);
      assertThat(r.actorId()).isEqualTo("multi-tour-security");
      assertThat(r.recordedAt()).isEqualTo(T10);
    });
    assertThat(store.cutoffs).containsKey(ACCOUNT);
  }

  @Test
  void A_an_older_event_still_makes_its_record_and_a_repeat_makes_none() {
    run(EVENT, T20);

    assertThat(run(OTHER_EVENT, T10)).isEqualTo(Outcome.UNCHANGED);
    assertThat(run(OTHER_EVENT, T10)).isEqualTo(Outcome.UNCHANGED);
    assertThat(store.audits).extracting(AuditRecord::eventId).containsExactly(EVENT, OTHER_EVENT);
  }

  @Test
  void A_the_first_event_of_an_account_sets_its_cutoff() {
    assertThat(run(EVENT, T10)).isEqualTo(Outcome.APPLIED);

    assertThat(store.cutoffs.get(ACCOUNT)).isEqualTo(new SessionCutoff(ACCOUNT, TENANT, T10, NOW));
    assertThat(store.processed).containsExactly(EVENT);
  }

  @Test
  void A_a_repeat_of_the_event_id_changes_nothing() {
    run(EVENT, T10);

    assertThat(run(EVENT, T20)).isEqualTo(Outcome.UNCHANGED);
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).isEqualTo(T10);
  }

  @Test
  void A_an_older_event_is_remembered_and_leaves_the_newer_cutoff() {
    run(EVENT, T20);

    assertThat(run(OTHER_EVENT, T10)).isEqualTo(Outcome.UNCHANGED);
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).isEqualTo(T20);
    assertThat(store.processed).containsExactlyInAnyOrder(EVENT, OTHER_EVENT);
  }

  @Test
  void A_a_newer_event_moves_the_cutoff_forward() {
    run(EVENT, T10);

    assertThat(run(OTHER_EVENT, T20)).isEqualTo(Outcome.APPLIED);
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).isEqualTo(T20);
  }

  @Test
  void A_a_race_decides_again_once_and_a_second_race_is_reported() {
    store.races = 1;
    assertThat(run(EVENT, T10)).isEqualTo(Outcome.APPLIED);

    store.races = 2;
    assertThatThrownBy(() -> run(OTHER_EVENT, T20)).isInstanceOf(ConcurrentChangeException.class);
  }

  @Test
  void A_an_event_of_another_tenant_for_a_known_account_is_refused_and_writes_nothing() {
    run(EVENT, T10);
    Command otherTenant = new Command(OTHER_EVENT, "AccountSessionsRevoked", "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", ACCOUNT, ACCOUNT, T20.toString());

    assertThatThrownBy(() -> apply.execute(otherTenant, event(OTHER_EVENT, T20)))
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("tenant of the account");
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).as("an account belongs to one tenant (ADR-004 D5)").isEqualTo(T10);
    assertThat(store.processed).containsExactly(EVENT);
  }

  private static Command commandWithNoTenant(String eventId, Instant from) {
    return new Command(eventId, "AccountSessionsRevoked", null, ACCOUNT, ACCOUNT, from.toString());
  }

  private static PlatformEvent eventWithNoTenant(String eventId, Instant from) {
    return new PlatformEvent(eventId, "AccountSessionsRevoked", null, ACCOUNT, null, null,
        Map.of("accountId", ACCOUNT, "revokedFrom", from.toString()), from);
  }

  @Test
  void A_a_revocation_with_no_tenant_is_accepted() {
    assertThat(apply.execute(commandWithNoTenant(EVENT, T10), eventWithNoTenant(EVENT, T10))).isEqualTo(Outcome.APPLIED);

    assertThat(store.cutoffs.get(ACCOUNT)).isEqualTo(new SessionCutoff(ACCOUNT, null, T10, NOW));
    assertThat(store.audits).singleElement().satisfies(r -> {
      assertThat(r.eventId()).isEqualTo(EVENT);
      assertThat(r.tenantId()).as("the account of the platform has no tenant").isNull();
    });
  }

  @Test
  void A_an_account_of_a_tenant_refuses_an_event_with_no_tenant_and_the_other_way_round() {
    run(EVENT, T10);
    assertThatThrownBy(() -> apply.execute(commandWithNoTenant(OTHER_EVENT, T20), eventWithNoTenant(OTHER_EVENT, T20)))
        .as("an account with a tenant, an event with none")
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("tenant of the account");
    assertThat(store.cutoffs.get(ACCOUNT)).isEqualTo(new SessionCutoff(ACCOUNT, TENANT, T10, NOW));
    assertThat(store.processed).containsExactly(EVENT);
    assertThat(store.audits).hasSize(1);

    FakeCutoffs platform = new FakeCutoffs();
    ApplySessionRevocation platformApply =
        new ApplySessionRevocation(platform, Clock.fixed(NOW, ZoneOffset.UTC), () -> "00000000-0000-4000-8000-0000000000aa");
    platformApply.execute(commandWithNoTenant(EVENT, T10), eventWithNoTenant(EVENT, T10));
    assertThatThrownBy(() -> platformApply.execute(command(OTHER_EVENT, T20), event(OTHER_EVENT, T20)))
        .as("an account with no tenant, an event with one")
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("tenant of the account");
    assertThat(platform.cutoffs.get(ACCOUNT)).isEqualTo(new SessionCutoff(ACCOUNT, null, T10, NOW));
    assertThat(platform.processed).containsExactly(EVENT);
    assertThat(platform.audits).hasSize(1);
  }

  @Test
  void A_an_event_that_is_not_a_valid_revocation_is_refused_with_its_field() {
    assertThatThrownBy(() -> new Command(EVENT, "TenantInactivated", TENANT, ACCOUNT, ACCOUNT, T10.toString()))
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("AccountSessionsRevoked");
    for (String bad : new String[] {null, "", "NOT-A-UUID", EVENT.toUpperCase(), EVENT + "\n"}) {
      assertThatThrownBy(() -> new Command(bad, "AccountSessionsRevoked", TENANT, ACCOUNT, ACCOUNT, T10.toString()))
          .as("event id %s", bad).isInstanceOf(InvalidDataException.class);
      if (bad != null) { // no tenant is the account of the platform; a tenant that is there must still be a UUID
        assertThatThrownBy(() -> new Command(EVENT, "AccountSessionsRevoked", bad, ACCOUNT, ACCOUNT, T10.toString()))
            .as("tenant id %s", bad).isInstanceOf(InvalidDataException.class);
      }
      assertThatThrownBy(() -> new Command(EVENT, "AccountSessionsRevoked", TENANT, bad, bad, T10.toString()))
          .as("account id %s", bad).isInstanceOf(InvalidDataException.class);
    }
    assertThatThrownBy(() -> new Command(EVENT, "AccountSessionsRevoked", TENANT, OTHER_EVENT, ACCOUNT, T10.toString()))
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("account of the payload");
    assertThatThrownBy(() -> new Command(EVENT, "AccountSessionsRevoked", TENANT, ACCOUNT, ACCOUNT, null))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> apply.execute(new Command(EVENT, "AccountSessionsRevoked", TENANT, ACCOUNT, ACCOUNT, "yesterday"), event(EVENT, T10)))
        .isInstanceOf(InvalidDataException.class).hasMessageContaining("RFC 3339");
    assertThat(store.processed).as("a refused event writes nothing").isEmpty();
    assertThat(store.audits).as("and leaves no record").isEmpty();
  }
}
