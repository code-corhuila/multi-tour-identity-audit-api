package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec 008 of the audit (HU-IDA-002 AC1): who did it and what it was about, by the type of the event and not always as the security service. */
class PlatformEventOriginTest {

  private static final String EVENT = "9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318";
  private static final String TENANT = "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11";
  private static final String AGGREGATE = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
  private static final String PERSON = "5e5e5e5e-5e5e-4e5e-8e5e-5e5e5e5e5e5e";
  private static final String OTHER_PERSON = "6f6f6f6f-6f6f-4f6f-8f6f-6f6f6f6f6f6f";
  private static final Instant AT = Instant.parse("2026-11-09T10:00:00Z");

  private static AuditRecord recorded(String type, String actor, Map<String, Object> payload) {
    return new PlatformEvent(EVENT, type, TENANT, AGGREGATE, actor, null, payload, AT).toAuditRecord("00000000-0000-4000-8000-0000000000aa");
  }

  @Test
  void D_actor_is_the_envelope_then_the_payload_then_the_service_of_the_type() {
    assertThat(recorded("SensitiveDataAccessed", OTHER_PERSON, Map.of("actorId", PERSON)).actorId()).isEqualTo(OTHER_PERSON);
    assertThat(recorded("SensitiveDataAccessed", null, Map.of("actorId", PERSON)).actorId()).isEqualTo(PERSON);
    assertThat(recorded("SensitiveDataAccessed", " ", Map.of("actorId", PERSON)).actorId()).isEqualTo(PERSON);
    assertThat(recorded("ReservationCreated", null, Map.of("reservationId", AGGREGATE)).actorId()).isEqualTo("multi-tour-commercial-reservation");
    assertThat(recorded("ReservationCreated", null, Map.of("actorId", 42)).actorId()).isEqualTo("multi-tour-commercial-reservation");
    assertThat(recorded("AccountBlocked", null, Map.of()).actorId()).isEqualTo("multi-tour-security");
  }

  @Test
  void D_record_prefix_follows_the_type_of_the_event() {
    for (String reservation : new String[] {"ReservationCreated", "ReservationModified", "CapacityAllocated", "CapacityReleased",
        "PaymentEvidenceRegistered", "PaymentValidated", "PaymentRejected", "ReservationConfirmed", "ReservationCancelled", "RefundAuthorized",
        "ClientNotified"}) {
      assertThat(recorded(reservation, null, Map.of()).affectedRecordId()).as(reservation).isEqualTo("reservations/" + AGGREGATE);
    }
    assertThat(recorded("CustomerRegistered", null, Map.of()).affectedRecordId()).isEqualTo("customers/" + AGGREGATE);
    assertThat(recorded("SensitiveDataAccessed", null, Map.of()).affectedRecordId()).isEqualTo("customers/" + AGGREGATE);
    assertThat(recorded("DiscountRuleChanged", null, Map.of()).affectedRecordId()).isEqualTo("discount-rules/" + AGGREGATE);
    assertThat(recorded("OptionalRoleEnabled", null, Map.of()).affectedRecordId()).isEqualTo("tenants/" + AGGREGATE);
    assertThat(recorded("AccountBlocked", null, Map.of()).affectedRecordId()).isEqualTo("accounts/" + AGGREGATE);
    assertThat(recorded("AccountEmailVerified", null, Map.of()).affectedRecordId()).isEqualTo("accounts/" + AGGREGATE);
  }

  @Test
  void D_an_event_of_an_unknown_type_is_recorded_as_before() {
    AuditRecord record = recorded("SomethingNewHappened", null, Map.of());

    assertThat(record.actorId()).isEqualTo("multi-tour-security");
    assertThat(record.affectedRecordId()).isEqualTo("accounts/" + AGGREGATE);
    assertThat(record.action()).isEqualTo("SOMETHING_NEW_HAPPENED");
  }
}
