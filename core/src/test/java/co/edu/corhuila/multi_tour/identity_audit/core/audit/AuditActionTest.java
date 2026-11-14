package co.edu.corhuila.multi_tour.identity_audit.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditActionTest {

  @Test
  void D_the_action_is_the_event_type_in_screaming_snake_case() {
    assertThat(AuditAction.of("AccountSessionsRevoked")).isEqualTo("ACCOUNT_SESSIONS_REVOKED");
    assertThat(AuditAction.of("SpecialPermissionGranted")).isEqualTo("SPECIAL_PERMISSION_GRANTED");
    assertThat(AuditAction.of("OptionalRoleDisabled")).isEqualTo("OPTIONAL_ROLE_DISABLED");
    assertThat(AuditAction.of("Tenant")).isEqualTo("TENANT");
  }

  @Test
  void D_every_event_that_exists_today_makes_a_known_action() {
    List<String> events = List.of("AccountSessionsRevoked", "AccountBlocked", "SpecialPermissionGranted", "SpecialPermissionRevoked",
        "OptionalRoleEnabled", "OptionalRoleDisabled", "TenantCreated", "TenantInactivated", "TenantReactivated",
        "AuditAccessEnabled", "AuditAccessDisabled",
        // the events of other services that the audit records (spec 008)
        "AccountEmailVerified", "ReservationCreated", "ReservationModified", "CapacityAllocated", "CapacityReleased",
        "PaymentEvidenceRegistered", "PaymentValidated", "PaymentRejected", "ReservationConfirmed", "ReservationCancelled",
        "RefundAuthorized", "ClientNotified", "CustomerRegistered", "DiscountRuleChanged", "SensitiveDataAccessed",
        // the exceptional access of the Platform Administrator to a tenant (spec 009)
        "PlatformExceptionalAccessOpened", "PlatformExceptionalAccessClosed",
        // each use of the token of that access (spec 012)
        "PlatformExceptionalAccessUsed");

    assertThat(events).allSatisfy(event -> assertThat(AuditAction.isKnown(AuditAction.of(event))).as(event).isTrue());
    assertThat(AuditAction.KNOWN).as("no action without an event").hasSize(events.size());
  }

  @Test
  void D_the_support_access_actions_are_known() {
    assertThat(AuditAction.of("PlatformExceptionalAccessOpened")).isEqualTo("PLATFORM_EXCEPTIONAL_ACCESS_OPENED");
    assertThat(AuditAction.of("PlatformExceptionalAccessClosed")).isEqualTo("PLATFORM_EXCEPTIONAL_ACCESS_CLOSED");
    assertThat(AuditAction.isKnown("PLATFORM_EXCEPTIONAL_ACCESS_OPENED")).isTrue();
    assertThat(AuditAction.isKnown("PLATFORM_EXCEPTIONAL_ACCESS_CLOSED")).isTrue();
    // spec 012: each use of the token of an access
    assertThat(AuditAction.isKnown("PLATFORM_EXCEPTIONAL_ACCESS_USED")).isTrue();
    assertThat(AuditAction.of("PlatformExceptionalAccessUsed")).isEqualTo("PLATFORM_EXCEPTIONAL_ACCESS_USED");
  }

  @Test
  void D_an_action_that_no_event_produces_is_not_known() {
    assertThat(AuditAction.isKnown("SOMETHING_ELSE")).isFalse();
    assertThat(AuditAction.isKnown(null)).isFalse();
    assertThat(AuditAction.isKnown("account_blocked")).as("the form is exact").isFalse();
  }

  @Test
  void D_an_action_of_exactly_64_characters_is_kept_and_one_of_65_is_refused() {
    String sevenLetters = "Abcdefg";
    String at64 = sevenLetters.repeat(7) + "Abcdefgh";     // 57 letters in 8 words, 7 underscores: 64
    String at65 = sevenLetters.repeat(7) + "Abcdefghi";    // 58 letters in 8 words, 7 underscores: 65
    String manyWords = "AbCd".repeat(16);                   // 64 letters in 32 words: 95 with the underscores

    assertThat(AuditAction.of(at64)).hasSize(64);
    assertThatThrownBy(() -> AuditAction.of(at65)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> AuditAction.of(manyWords)).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_the_conversion_does_not_depend_on_the_locale_of_the_machine() {
    java.util.Locale before = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
      assertThat(AuditAction.of("AccountBlocked")).isEqualTo("ACCOUNT_BLOCKED");
      assertThat(AuditAction.of("TenantInactivated")).isEqualTo("TENANT_INACTIVATED");
    } finally {
      java.util.Locale.setDefault(before);
    }
  }

  @Test
  void D_an_event_type_that_is_not_a_name_is_refused() {
    assertThatThrownBy(() -> AuditAction.of("account blocked")).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> AuditAction.of("")).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> AuditAction.of(null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> AuditAction.of("lowercase")).isInstanceOf(InvalidDataException.class);
  }
}
