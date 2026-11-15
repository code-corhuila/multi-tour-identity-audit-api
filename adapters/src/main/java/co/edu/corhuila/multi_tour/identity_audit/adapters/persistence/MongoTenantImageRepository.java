package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageKind;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSlot;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ImageSummary;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantImage;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantImages;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;

/**
 * The files of the tenants in {@code tenant_images}, one document per tenant, kind and position (the unique index of
 * {@code identity-audit-db} spec 006). A save is one upsert on that key, so a replace never leaves the tenant without the file, and the
 * numbers are written as 32-bit integers, which is what the validator asks for.
 */
public final class MongoTenantImageRepository implements TenantImages {

  private final MongoClient client;
  private final String database;

  public MongoTenantImageRepository(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  private MongoCollection<Document> images() {
    return client.getDatabase(database).getCollection("tenant_images");
  }

  private static Bson place(String tenantId, ImageSlot slot, int position) {
    return and(eq("tenantId", tenantId), eq("kind", slot.name()), eq("position", position));
  }

  @Override
  public void save(TenantImage image) {
    Bson update = combine(
        set("contentType", image.kind().contentType()), set("sizeBytes", image.sizeBytes()), set("sha256", image.sha256()),
        set("data", new Binary(image.bytes())), set("updatedBy", image.updatedBy()), set("updatedAt", Date.from(image.updatedAt())),
        setOnInsert("_id", UUID.randomUUID().toString()));
    Bson filter = place(image.tenantId(), image.slot(), image.position());
    try {
      images().updateOne(filter, update, new UpdateOptions().upsert(true));
    } catch (MongoWriteException first) {
      if (first.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
        throw first;
      }
      // two upserts of a place that did not exist raced (or a remove landed between): the other one won, so this is now a plain update
      images().updateOne(filter, update, new UpdateOptions().upsert(true));
    }
  }

  @Override
  public void remove(String tenantId, ImageSlot slot, int position) {
    images().deleteOne(place(tenantId, slot, position));
  }

  @Override
  public Optional<TenantImage> find(String tenantId, ImageSlot slot, int position) {
    Document document = ReadLimit.of(images().find(place(tenantId, slot, position))).first();
    if (document == null) {
      return Optional.empty();
    }
    ImageKind kind = Arrays.stream(ImageKind.values()).filter(k -> k.contentType().equals(document.getString("contentType"))).findFirst()
        .orElseThrow(() -> new IllegalStateException("A stored file has a content type that is not allowed"));
    return Optional.of(new TenantImage(tenantId, slot, position, kind, document.get("data", Binary.class).getData(),
        document.getString("sha256"), document.getString("updatedBy"), document.getDate("updatedAt").toInstant()));
  }

  @Override
  public List<ImageSummary> list(String tenantId) {
    List<ImageSummary> found = new ArrayList<>();
    ReadLimit.of(images().find(eq("tenantId", tenantId)).projection(Projections.include("kind", "position", "sha256")))
        .forEach(d -> found.add(new ImageSummary(ImageSlot.valueOf(d.getString("kind")), d.getInteger("position"), d.getString("sha256"))));
    // IMAGE sorts before LOGO as text, so the order is made here: the logo first, then the images by position
    found.sort(Comparator.comparing((ImageSummary s) -> s.slot() == ImageSlot.LOGO ? 0 : 1).thenComparing(ImageSummary::position));
    return found;
  }
}
