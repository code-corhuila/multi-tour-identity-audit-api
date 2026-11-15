package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageKind;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSlot;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSummary;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantImage;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The files of the tenants in {@code tenant_images}, in the database that the db repository builds (spec 012, D-02 and D-05). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MongoTenantImageRepositoryTest {

  private static final Instant T1 = Instant.parse("2026-11-12T10:00:00.123Z");
  private static final String TENANT = "00000000-0000-4000-8000-0000000000b1";
  private static final String OTHER = "00000000-0000-4000-8000-0000000000b2";
  private static final String ADMIN = "00000000-0000-4000-8000-0000000000a1";

  private ConnectionString url;
  private MongoClient client;
  private MongoTenantImageRepository repository;

  @BeforeEach
  void aCleanDatabase() {
    url = new ConnectionString(System.getenv("TEST_DATABASE_URL"));
    client = MongoClients.create(url);
    client.getDatabase(url.getDatabase()).getCollection("tenant_images").deleteMany(new Document());
    repository = new MongoTenantImageRepository(client, url.getDatabase());
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static byte[] png(int size) {
    byte[] bytes = new byte[size];
    byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    bytes[size - 1] = (byte) size;
    return bytes;
  }

  private static TenantImage image(String tenantId, ImageSlot slot, int position, int size) {
    return new TenantImage(tenantId, slot, position, ImageKind.PNG, png(size), "a".repeat(64), ADMIN, T1);
  }

  private long count() {
    return client.getDatabase(url.getDatabase()).getCollection("tenant_images").countDocuments();
  }

  @Test
  void I_a_file_is_stored_and_read_back_with_everything_it_had() {
    TenantImage saved = new TenantImage(TENANT, ImageSlot.IMAGE, 3, ImageKind.WEBP, png(500), "b".repeat(64), ADMIN, T1);
    repository.save(saved);
    TenantImage read = repository.find(TENANT, ImageSlot.IMAGE, 3).orElseThrow();
    assertThat(read.bytes()).isEqualTo(saved.bytes());
    assertThat(read.kind()).isEqualTo(ImageKind.WEBP);
    assertThat(read.sha256()).isEqualTo("b".repeat(64));
    assertThat(read.sizeBytes()).isEqualTo(500);
    assertThat(read.updatedBy()).isEqualTo(ADMIN);
    assertThat(read.updatedAt()).isEqualTo(T1);
    assertThat(read.tenantId()).isEqualTo(TENANT);
    assertThat(repository.find(TENANT, ImageSlot.IMAGE, 2)).isEmpty();
  }

  @Test
  void I_the_edges_of_the_validator_are_accepted_through_save() {
    repository.save(image(TENANT, ImageSlot.IMAGE, 4, 1024 * 1024));
    repository.save(image(TENANT, ImageSlot.IMAGE, 0, 1));
    assertThat(repository.find(TENANT, ImageSlot.IMAGE, 4).orElseThrow().sizeBytes()).isEqualTo(1024 * 1024);
    assertThat(repository.find(TENANT, ImageSlot.IMAGE, 0).orElseThrow().sizeBytes()).isEqualTo(1);
  }

  @Test
  void I_saving_at_a_place_replaces_the_file_and_keeps_one_document() {
    repository.save(image(TENANT, ImageSlot.LOGO, 0, 100));
    repository.save(image(TENANT, ImageSlot.LOGO, 0, 200));
    assertThat(count()).isEqualTo(1);
    assertThat(repository.find(TENANT, ImageSlot.LOGO, 0).orElseThrow().sizeBytes()).isEqualTo(200);
  }

  @Test
  void I_removing_takes_the_file_away_and_removing_nothing_is_not_an_error() {
    repository.save(image(TENANT, ImageSlot.IMAGE, 1, 100));
    repository.save(image(OTHER, ImageSlot.IMAGE, 1, 100));
    repository.remove(TENANT, ImageSlot.IMAGE, 1);
    repository.remove(TENANT, ImageSlot.IMAGE, 1);
    repository.remove(TENANT, ImageSlot.LOGO, 0);
    assertThat(repository.find(TENANT, ImageSlot.IMAGE, 1)).isEmpty();
    assertThat(repository.find(OTHER, ImageSlot.IMAGE, 1)).isPresent();
  }

  @Test
  void I_the_list_of_a_tenant_has_the_logo_first_then_the_images_by_position_and_no_bytes() {
    repository.save(image(TENANT, ImageSlot.IMAGE, 4, 40));
    repository.save(image(TENANT, ImageSlot.IMAGE, 0, 41));
    repository.save(image(TENANT, ImageSlot.LOGO, 0, 42));
    repository.save(image(OTHER, ImageSlot.LOGO, 0, 43));
    List<ImageSummary> list = repository.list(TENANT);
    assertThat(list).extracting(ImageSummary::slot).containsExactly(ImageSlot.LOGO, ImageSlot.IMAGE, ImageSlot.IMAGE);
    assertThat(list).extracting(ImageSummary::position).containsExactly(0, 0, 4);
    assertThat(list).extracting(ImageSummary::sha256).containsOnly("a".repeat(64));
    assertThat(repository.list("00000000-0000-4000-8000-0000000000b9")).isEmpty();
  }

  @Test
  void I_two_saves_at_the_same_place_at_once_leave_one_file_and_both_succeed() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int round = 0; round < 10; round++) {
        repository.remove(TENANT, ImageSlot.LOGO, 0);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> saves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          int size = 50 + i;
          saves.add(pool.submit(() -> {
            go.await();
            repository.save(image(TENANT, ImageSlot.LOGO, 0, size));
            return null;
          }));
        }
        go.countDown();
        for (Future<?> save : saves) {
          save.get();
        }
        assertThat(count()).as("round %d", round).isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
