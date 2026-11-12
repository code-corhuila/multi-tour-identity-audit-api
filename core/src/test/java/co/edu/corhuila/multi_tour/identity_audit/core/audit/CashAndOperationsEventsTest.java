package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec 008 of the audit, amended 2026-11-12 (AC-IDA-08.6 to 08.8, HU-IDA-002): the events of cash and of operations have their own actor and record. */
class CashAndOperationsEventsTest {

  private static final String AGGREGATE = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String PERSON = "5e5e5e5e-5e5e-4e5e-8e5e-5e5e5e5e5e5e";
  private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");

  private static AuditRecord recorded(String type, String actor) {
    return new PlatformEvent("9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318", type, "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11", AGGREGATE, actor, null, Map.of(), AT)
        .toAuditRecord("00000000-0000-4000-8000-0000000000aa");
  }

  @Test
  void D_cash_events_are_recorded_against_their_session_movement_or_refund_by_the_cash_service() {
    for (String session : new String[] {"CashSessionOpened", "CashSessionClosed", "CashSessionAutoCreated", "CashSessionAutoClosed", "CashSessionReopened"}) {
      assertThat(recorded(session, null).affectedRecordId()).as(session).isEqualTo("cash-sessions/" + AGGREGATE);
    }
    for (String movement : new String[] {"CashIncomeRecorded", "OperationalPaymentRecorded", "OperationalExpenseRecorded"}) {
      assertThat(recorded(movement, null).affectedRecordId()).as(movement).isEqualTo("cash-movements/" + AGGREGATE);
    }
    assertThat(recorded("RefundExecuted", null).affectedRecordId()).isEqualTo("refunds/" + AGGREGATE);
    assertThat(recorded("CashSessionOpened", null).actorId()).isEqualTo("multi-tour-cash-reporting");
    assertThat(recorded("RefundExecuted", null).actorId()).isEqualTo("multi-tour-cash-reporting");
    // a person named by the envelope still wins, as for every service
    assertThat(recorded("CashIncomeRecorded", PERSON).actorId()).isEqualTo(PERSON);
  }

  @Test
  void D_operations_events_are_recorded_against_the_departure_or_the_cost_by_the_operations_service() {
    for (String departure : new String[] {"SalidaScheduled", "SalidaRescheduled", "SalidaVehiclesChanged", "SalidaExecutionStarted", "SalidaFinalized",
        "SalidaMarkedPendienteDeRegularizacion", "SalidaCancelled", "ExtraordinaryCancellationRecorded"}) {
      assertThat(recorded(departure, null).affectedRecordId()).as(departure).isEqualTo("salidas/" + AGGREGATE);
      assertThat(recorded(departure, null).actorId()).as(departure).isEqualTo("multi-tour-operations-cost");
    }
    assertThat(recorded("OperationalCostRecorded", null).affectedRecordId()).isEqualTo("operational-costs/" + AGGREGATE);
    assertThat(recorded("OperationalCostRecorded", null).actorId()).isEqualTo("multi-tour-operations-cost");
    assertThat(recorded("SalidaScheduled", PERSON).actorId()).isEqualTo(PERSON);
  }

  @Test
  void D_the_actions_of_cash_operations_and_the_recovery_are_known() {
    for (String event : new String[] {"CashSessionOpened", "CashSessionClosed", "CashSessionAutoCreated", "CashSessionAutoClosed", "CashSessionReopened",
        "CashIncomeRecorded", "OperationalPaymentRecorded", "OperationalExpenseRecorded", "RefundExecuted", "SalidaScheduled", "SalidaRescheduled",
        "SalidaVehiclesChanged", "SalidaExecutionStarted", "SalidaFinalized", "SalidaMarkedPendienteDeRegularizacion", "SalidaCancelled", "ExtraordinaryCancellationRecorded",
        "OperationalCostRecorded", "PasswordRecoveryRequested", "PasswordReset"}) {
      assertThat(AuditAction.isKnown(AuditAction.of(event))).as(event).isTrue();
    }
    assertThat(AuditAction.isKnown("CASH_SESSION_NOPE")).isFalse();
  }
}
