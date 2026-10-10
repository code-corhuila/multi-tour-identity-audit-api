package co.edu.corhuila.multi_tour.identity_audit.adapters.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdentifierTakenException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKey;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RepeatKeyUsedException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Tenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.ErrorCategory;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;

/**
 * The tenants in the database {@code identity_audit}, which {@code multi-tour-identity-audit-db} builds: its
 * unique indexes decide who holds an identifier and who stored a repeat key first, and every write that
 * touches more than one document runs in one transaction (plan D-05; the instance is a replica set).
 */
public final class MongoTenantRepository implements TenantRepository {

  private static final List<String> LIVE = List.of("PENDING", "ACTIVE", "INACTIVE");
  /** A committed write must survive a failover, whatever the URI of the connection says. */
  private static final TransactionOptions DURABLE =
      TransactionOptions.builder().readConcern(ReadConcern.MAJORITY).writeConcern(WriteConcern.MAJORITY).build();

  private final MongoClient client;
  private final String database;

  public MongoTenantRepository(MongoClient client, String database) {
    this.client = client;
    this.database = database;
  }

  private MongoCollection<Document> tenants() {
    return client.getDatabase(database).getCollection("tenants");
  }

  private MongoCollection<Document> keys() {
    return client.getDatabase(database).getCollection("idempotency_keys");
  }

  @Override
  public Optional<Tenant> findById(String tenantId) {
    return Optional.ofNullable(tenants().find(eq("_id", tenantId)).first()).map(TenantDocuments::toTenant);
  }

  @Override
  public Optional<Tenant> findByIdentifier(String identifier) {
    return Optional.ofNullable(tenants().find(and(eq("identifier", identifier), in("tenantStatus", LIVE))).first())
        .map(TenantDocuments::toTenant);
  }

  @Override
  public Optional<Tenant> findByRepeatKey(RepeatKey key) {
    Document stored = keys().find(and(eq("operation", key.operation()), eq("key", key.key()))).first();
    return stored == null ? Optional.empty() : findById(stored.getString("resourceId"));
  }

  @Override
  public void insert(Tenant tenant, RepeatKey key) {
    try (ClientSession session = client.startSession()) {
      session.withTransaction(() -> {
        // the key goes first, so that when both conflict it is the one reported (a repeat of the first request)
        try {
          keys().insertOne(session, new Document("key", key.key()).append("operation", key.operation())
              .append("resourceId", tenant.tenantId()).append("createdAt", Date.from(tenant.createdAt())));
        } catch (MongoWriteException e) {
          throw duplicate(e, "idx_idempotency_keys_operation_key") ? new RepeatKeyUsedException(key) : e;
        }
        try {
          tenants().insertOne(session, TenantDocuments.toDocument(tenant));
        } catch (MongoWriteException e) {
          throw duplicate(e, "idx_tenants_identifier") ? new IdentifierTakenException(tenant.identifier()) : e;
        }
        return null;
      }, DURABLE);
    }
  }

  /** A duplicate key on that index, and not on another one such as the _id. */
  private static boolean duplicate(MongoWriteException e, String index) {
    return e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY && e.getError().getMessage().contains(index);
  }

  @Override
  public Page<Tenant> list(TenantStatus status, int page, int limit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void update(Tenant expected, Tenant next, List<TenantEvent> events) {
    throw new UnsupportedOperationException();
  }
}
