package co.edu.corhuila.multi_tour.identity_audit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerTest {

  @Test
  void D_a_platform_administrator_has_no_tenant() {
    assertThatThrownBy(() -> new Caller("admin", "tenant-1", Set.of(Role.PLATFORM_ADMINISTRATOR)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_a_platform_administrator_and_a_service_may_have_no_tenant() {
    assertThat(new Caller("admin", null, Set.of(Role.PLATFORM_ADMINISTRATOR)).roles()).hasSize(1);
    assertThat(new Caller("event-relay", "", Set.of(Role.SERVICE)).tenantId()).isNull();
  }

  @Test
  void D_a_caller_needs_a_subject_and_at_least_one_role() {
    assertThatThrownBy(() -> new Caller(" ", null, Set.of(Role.SERVICE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Caller("someone", null, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
