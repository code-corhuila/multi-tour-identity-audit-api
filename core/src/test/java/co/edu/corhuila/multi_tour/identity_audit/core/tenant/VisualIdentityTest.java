package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VisualIdentityTest {

  @Test
  void D_color_must_be_hash_and_six_hex_digits() {
    for (String bad : new String[] {"1A2B3C", "#1A2B3", "#1A2B3CD", "#1A2B3G", "#1A2B3C\n", "#1A2B3C ", "", " ", null}) {
      assertThatThrownBy(() -> new VisualIdentity(bad, "#000000")).as("primary %s", bad)
          .isInstanceOf(InvalidDataException.class).hasMessageContaining("primary");
      assertThatThrownBy(() -> new VisualIdentity("#000000", bad)).as("secondary %s", bad)
          .isInstanceOf(InvalidDataException.class).hasMessageContaining("secondary");
    }
  }

  @Test
  void D_colors_are_normalized_to_capitals() {
    VisualIdentity identity = new VisualIdentity("#1a2b3c", "#aBcDeF");
    assertThat(identity.primaryColor()).isEqualTo("#1A2B3C");
    assertThat(identity.secondaryColor()).isEqualTo("#ABCDEF");
    assertThat(identity).isEqualTo(new VisualIdentity("#1A2B3C", "#abcdef"));
  }
}