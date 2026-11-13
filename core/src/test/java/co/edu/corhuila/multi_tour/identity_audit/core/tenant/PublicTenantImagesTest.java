package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What anyone with the link reads of the logo and the images: the profile and the files (spec 012, RN-IDA-API-123 to 125). */
class PublicTenantImagesTest {

  private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
  private static final String A = "00000000-0000-4000-8000-00000000000a";
  private static final String B = "00000000-0000-4000-8000-00000000000b";
  private static final String P = "00000000-0000-4000-8000-0000000000cc";
  private static final String MEMBERSHIP = "00000000-0000-4000-8000-0000000000aa";

  private final InMemoryTenants tenants = new InMemoryTenants();
  private final InMemoryTenantImages images = new InMemoryTenantImages();
  private final GetPublicProfile profile = new GetPublicProfile(tenants, images);
  private final GetPublicImage image = new GetPublicImage(tenants, images);

  @BeforeEach
  void tenantsOfEveryStatus() {
    for (String id : new String[] {A, B}) {
      tenants.tenants.put(id, Tenant.registerPending(id, "tenant-" + id.substring(35), "Tenant " + id.substring(35), "America/Bogota", "CO", T0)
          .completeOnboarding(MEMBERSHIP, TenantStatus.ACTIVE, T0));
    }
    tenants.tenants.put(P, Tenant.registerPending(P, "tenant-cc", "Pending", "America/Bogota", "CO", T0));
  }

  private TenantImage file(String tenantId, ImageSlot slot, int position, String sha) {
    TenantImage stored = new TenantImage(tenantId, slot, position, ImageKind.PNG, ImageKindTest.png(20), sha, "account-1", T0);
    images.save(stored);
    return stored;
  }

  @Test
  void A_a_tenant_without_a_logo_or_images_returns_none_so_the_default_identity_shows() {
    PublicTenantProfile found = profile.execute("tenant-a");
    assertThat(found.logo()).isNull();
    assertThat(found.images()).isEmpty();
  }

  @Test
  void A_the_profile_returns_the_logo_and_the_images_by_position_and_only_those_of_its_tenant() {
    file(A, ImageSlot.IMAGE, 3, "c".repeat(64));
    file(A, ImageSlot.LOGO, 0, "a".repeat(64));
    file(A, ImageSlot.IMAGE, 1, "b".repeat(64));
    file(B, ImageSlot.LOGO, 0, "d".repeat(64));
    PublicTenantProfile found = profile.execute("tenant-a");
    assertThat(found.logo()).isEqualTo(new ImageSummary(ImageSlot.LOGO, 0, "a".repeat(64)));
    assertThat(found.images()).extracting(ImageSummary::position).containsExactly(1, 3);
    assertThat(profile.execute("tenant-b").logo().sha256()).isEqualTo("d".repeat(64));
  }

  @Test
  void A_a_file_is_returned_with_its_bytes_and_the_kind_that_was_proved() {
    TenantImage stored = file(A, ImageSlot.IMAGE, 2, "e".repeat(64));
    TenantImage read = image.execute("tenant-a", ImageSlot.IMAGE, 2);
    assertThat(read.bytes()).isEqualTo(stored.bytes());
    assertThat(read.kind()).isEqualTo(ImageKind.PNG);
  }

  @Test
  void A_an_unknown_or_pending_tenant_a_bad_identifier_or_a_missing_file_is_not_found() {
    file(P, ImageSlot.LOGO, 0, "f".repeat(64));
    for (String identifier : new String[] {"nobody", "tenant-cc", "Bad Id", "", "a"}) {
      assertThatThrownBy(() -> image.execute(identifier, ImageSlot.LOGO, 0)).as(identifier).isInstanceOf(TenantNotFoundException.class);
    }
    assertThatThrownBy(() -> image.execute("tenant-a", ImageSlot.LOGO, 0)).isInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> image.execute("tenant-a", ImageSlot.IMAGE, 9)).isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void A_an_inactive_tenant_is_served_as_it_is() {
    file(A, ImageSlot.LOGO, 0, "a".repeat(64));
    tenants.tenants.put(A, tenants.tenants.get(A).inactivate("platform", "reason", T0));
    assertThat(image.execute("tenant-a", ImageSlot.LOGO, 0).bytes()).hasSize(20);
    assertThat(profile.execute("tenant-a").logo()).isNotNull();
  }
}
