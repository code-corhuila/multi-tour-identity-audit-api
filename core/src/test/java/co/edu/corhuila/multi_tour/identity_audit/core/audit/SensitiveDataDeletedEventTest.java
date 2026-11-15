package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec 013 of the audit (AC-IDA-13.1, AC-IDA-13.2, HU-IDA-002): the deletion of the sensitive data of a reservation is recorded and can be filtered. */
class SensitiveDataDeletedEventTest {

  private static final String RESERVATION = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";

  @Test
  void D_the_deletion_of_sensitive_data_is_recorded_against_the_reservation() {
    AuditRecord recorded = new PlatformEvent("9b1c7d52-8a3e-4f60-b1d4-27e5c0a9f318", "SensitiveDataDeleted", "3f2b8c1e-5d4a-4e7b-9a10-6c2d8e4f0a11",
        RESERVATION, null, null, Map.of(), Instant.parse("2026-11-14T10:00:00Z")).toAuditRecord("00000000-0000-4000-8000-0000000000aa");

    // the retention worker deletes the data, so no person is the actor
    assertThat(recorded.actorId()).isEqualTo("multi-tour-commercial-reservation");
    assertThat(recorded.affectedRecordId()).isEqualTo("reservations/" + RESERVATION);
  }

  @Test
  void D_the_action_of_the_deletion_of_sensitive_data_is_known() {
    assertThat(AuditAction.of("SensitiveDataDeleted")).isEqualTo("SENSITIVE_DATA_DELETED");
    assertThat(AuditAction.isKnown("SENSITIVE_DATA_DELETED")).isTrue();
    assertThat(AuditAction.isKnown("SENSITIVE_DATA_NOPE")).isFalse();
  }
}
