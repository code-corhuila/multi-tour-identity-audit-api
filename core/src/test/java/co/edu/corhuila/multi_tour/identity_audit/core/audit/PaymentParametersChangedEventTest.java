package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec 013 of the audit (amendment of 2026-11-14, HU-IDA-002): the change of a payment parameter is recorded and can be filtered. */
class PaymentParametersChangedEventTest {

  private static final String CONDITION = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d";
  private static final String AUTHOR = "2d4f6a8c-0e1b-4c3d-9a5b-7f8e9d0c1b2a";

  private static PlatformEvent change(String envelopeActor, Map<String, Object> payload) {
    return new PlatformEvent("9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318", "PaymentParametersChanged", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11",
        CONDITION, envelopeActor, null, payload, Instant.parse("2026-11-14T10:00:00Z"));
  }

  @Test
  void D_the_change_is_recorded_against_the_payment_condition_with_its_author() {
    AuditRecord recorded = change(null, Map.of("actorId", AUTHOR, "paymentType", "PARTIAL"))
        .toAuditRecord("00000000-0000-4000-8000-0000000000aa");

    assertThat(recorded.actorId()).isEqualTo(AUTHOR);
    assertThat(recorded.affectedRecordId()).isEqualTo("payment-conditions/" + CONDITION);
  }

  @Test
  void D_without_a_person_the_reservation_service_is_the_actor_and_not_the_security_service() {
    AuditRecord recorded = change(null, Map.of()).toAuditRecord("00000000-0000-4000-8000-0000000000aa");

    assertThat(recorded.actorId()).isEqualTo("multi-tour-commercial-reservation");
  }

  @Test
  void D_the_action_of_a_payment_parameter_change_is_known() {
    assertThat(AuditAction.of("PaymentParametersChanged")).isEqualTo("PAYMENT_PARAMETERS_CHANGED");
    assertThat(AuditAction.isKnown("PAYMENT_PARAMETERS_CHANGED")).isTrue();
    assertThat(AuditAction.isKnown("PAYMENT_PARAMETERS_NOPE")).isFalse();
  }
}
