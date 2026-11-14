package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Save, replace and remove the logo and the commercial images (spec 012: RN-IDA-API-120 to 123). */
class SaveTenantImageTest {

  private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-12T10:00:00Z");
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";
  private static final int LOGO_LIMIT = 256 * 1024;
  private static final int IMAGE_LIMIT = 1024 * 1024;

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final InMemoryTenantImages images = new InMemoryTenantImages();
  private final Clock clock = Clock.fixed(T1, ZoneOffset.UTC);
  private final SaveTenantImage save = new SaveTenantImage(tenants, images, clock);
  private final RemoveTenantImage remove = new RemoveTenantImage(tenants, images);

  @BeforeEach
  void twoActiveTenants() {
    for (String id : new String[] {A, B}) {
      tenants.tenants.put(id, Tenant.registerPending(id, "tenant-" + id.substring(35), "Tenant", "America/Bogota", "CO", T0)
          .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
    }
  }

  private static Caller administratorOf(String tenantId) {
    return new Caller("account-1", tenantId, Set.of(Role.TENANT_ADMINISTRATOR));
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  @Test
  void A_the_tenant_comes_from_the_token() throws Exception {
    byte[] file = ImageKindTest.png(100);
    Tenant saved = save.execute(administratorOf(A), ImageSlot.LOGO, 0, "image/png", b64(file));
    assertThat(saved.tenantId()).isEqualTo(A);
    TenantImage stored = images.at(A, ImageSlot.LOGO, 0);
    assertThat(stored.bytes()).isEqualTo(file);
    assertThat(stored.kind()).isEqualTo(ImageKind.PNG);
    assertThat(stored.sizeBytes()).isEqualTo(100);
    assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)));
    assertThat(stored.updatedBy()).isEqualTo("account-1");
    assertThat(stored.updatedAt()).isEqualTo(T1);
    assertThat(images.at(B, ImageSlot.LOGO, 0)).isNull();
  }

  @Test
  void A_any_other_role_and_a_caller_without_a_tenant_are_refused_and_nothing_is_written() {
    Caller[] refused = {
        new Caller("account-1", null, Set.of(Role.PLATFORM_ADMINISTRATOR)),
        new Caller("worker", null, Set.of(Role.SERVICE)),
        new Caller("account-2", A, Set.of(Role.FINAL_CUSTOMER)),
        new Caller("account-3", A, Set.of(Role.MANAGER)),
        new Caller("account-4", null, Set.of(Role.TENANT_ADMINISTRATOR))};
    String data = b64(ImageKindTest.png(10));
    for (Caller caller : refused) {
      assertThatThrownBy(() -> save.execute(caller, ImageSlot.LOGO, 0, "image/png", data))
          .as("%s", caller).isInstanceOf(CallerNotAllowedException.class);
      assertThatThrownBy(() -> remove.execute(caller, ImageSlot.LOGO, 0))
          .as("%s", caller).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_saving_at_a_position_replaces_it() {
    save.execute(administratorOf(A), ImageSlot.IMAGE, 2, "image/png", b64(ImageKindTest.png(50)));
    byte[] second = ImageKindTest.jpeg(70);
    save.execute(administratorOf(A), ImageSlot.IMAGE, 2, "image/jpeg", b64(second));
    assertThat(images.files).hasSize(1);
    assertThat(images.at(A, ImageSlot.IMAGE, 2).bytes()).isEqualTo(second);
    assertThat(images.at(A, ImageSlot.IMAGE, 2).kind()).isEqualTo(ImageKind.JPEG);
    save.execute(administratorOf(A), ImageSlot.IMAGE, 0, "image/webp", b64(ImageKindTest.webp(70)));
    assertThat(images.files).hasSize(2);
  }

  @Test
  void D_the_size_limits() {
    Caller admin = administratorOf(A);
    save.execute(admin, ImageSlot.LOGO, 0, "image/png", b64(ImageKindTest.png(LOGO_LIMIT)));
    save.execute(admin, ImageSlot.IMAGE, 0, "image/png", b64(ImageKindTest.png(IMAGE_LIMIT)));
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 0, "image/png", b64(ImageKindTest.png(LOGO_LIMIT + 1))))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("data"));
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.IMAGE, 1, "image/png", b64(ImageKindTest.png(IMAGE_LIMIT + 1))))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("data"));
    assertThat(images.files).hasSize(2);
  }

  @Test
  void A_a_file_that_is_not_allowed_is_refused_and_the_previous_one_stays() {
    Caller admin = administratorOf(A);
    byte[] first = ImageKindTest.png(40);
    save.execute(admin, ImageSlot.LOGO, 0, "image/png", b64(first));
    String gif = b64("GIF89a-----------".getBytes());
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 0, "image/png", gif))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("data"));
    // the declared kind must agree with the bytes
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 0, "image/jpeg", b64(ImageKindTest.png(40))))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("contentType"));
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 0, "image/svg+xml", b64(ImageKindTest.png(40))))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("contentType"));
    assertThat(images.at(A, ImageSlot.LOGO, 0).bytes()).isEqualTo(first);
  }

  @Test
  void A_data_that_is_not_strict_base64_or_is_empty_is_refused() {
    Caller admin = administratorOf(A);
    String ok = b64(ImageKindTest.png(40));
    String[] refused = {"", " ", ok + "\n", "!!!!", "A", "QQ", ok.replace('+', '-').replace('/', '_') + "-", null};
    for (String data : refused) {
      assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 0, "image/png", data))
          .as("%s", data).isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("data"));
    }
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_a_position_out_of_range_is_refused() {
    Caller admin = administratorOf(A);
    String data = b64(ImageKindTest.png(40));
    for (int position : new int[] {-1, 5, 99}) {
      assertThatThrownBy(() -> save.execute(admin, ImageSlot.IMAGE, position, "image/png", data))
          .as("%d", position).isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("position"));
      assertThatThrownBy(() -> remove.execute(admin, ImageSlot.IMAGE, position))
          .as("%d", position).isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("position"));
    }
    assertThatThrownBy(() -> save.execute(admin, ImageSlot.LOGO, 1, "image/png", data))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("position"));
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_an_inactive_tenant_cannot_change_them_and_an_unknown_one_is_not_found() {
    tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform", "reason", T0));
    String data = b64(ImageKindTest.png(40));
    assertThatThrownBy(() -> save.execute(administratorOf(A), ImageSlot.LOGO, 0, "image/png", data))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> remove.execute(administratorOf(A), ImageSlot.LOGO, 0))
        .isInstanceOf(BusinessRuleViolationException.class);
    String missing = "00000000-0000-4000-8000-0000000000ff";
    assertThatThrownBy(() -> save.execute(administratorOf(missing), ImageSlot.LOGO, 0, "image/png", data))
        .isInstanceOf(TenantNotFoundException.class);
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_an_administrator_cannot_touch_the_files_of_another_tenant() {
    byte[] ofB = ImageKindTest.png(30);
    save.execute(administratorOf(B), ImageSlot.IMAGE, 1, "image/png", b64(ofB));
    save.execute(administratorOf(A), ImageSlot.IMAGE, 1, "image/png", b64(ImageKindTest.png(40)));
    remove.execute(administratorOf(A), ImageSlot.IMAGE, 1);
    assertThat(images.at(B, ImageSlot.IMAGE, 1).bytes()).isEqualTo(ofB);
    assertThat(images.at(A, ImageSlot.IMAGE, 1)).isNull();
  }

  @Test
  void A_the_token_of_an_exceptional_access_is_refused() {
    Caller exceptional = new Caller("platform-admin", A, Set.of(Role.PLATFORM_ADMINISTRATOR), true);
    assertThatThrownBy(() -> save.execute(exceptional, ImageSlot.LOGO, 0, "image/png", b64(ImageKindTest.png(10))))
        .isInstanceOf(CallerNotAllowedException.class);
    assertThatThrownBy(() -> remove.execute(exceptional, ImageSlot.LOGO, 0)).isInstanceOf(CallerNotAllowedException.class);
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_a_tenant_that_has_not_finished_its_onboarding_cannot_change_them() {
    String pending = "00000000-0000-4000-8000-0000000000cc";
    tenants.tenants.put(pending, Tenant.registerPending(pending, "pending-tours", "Tenant", "America/Bogota", "CO", T0));
    assertThatThrownBy(() -> save.execute(administratorOf(pending), ImageSlot.LOGO, 0, "image/png", b64(ImageKindTest.png(10))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(images.files).isEmpty();
  }

  @Test
  void A_removing_what_is_not_there_changes_nothing_and_removing_what_is_there_takes_it_away() {
    Caller admin = administratorOf(A);
    remove.execute(admin, ImageSlot.LOGO, 0);
    save.execute(admin, ImageSlot.LOGO, 0, "image/png", b64(ImageKindTest.png(40)));
    save.execute(admin, ImageSlot.IMAGE, 3, "image/png", b64(ImageKindTest.png(40)));
    remove.execute(admin, ImageSlot.IMAGE, 3);
    assertThat(images.at(A, ImageSlot.IMAGE, 3)).isNull();
    assertThat(images.at(A, ImageSlot.LOGO, 0)).isNotNull();
  }
}
