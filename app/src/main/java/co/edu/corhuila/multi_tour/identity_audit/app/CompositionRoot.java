package co.edu.corhuila.multi_tour.identity_audit.app;

import co.edu.corhuila.multi_tour.identity_audit.adapters.persistence.MongoTenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CompleteOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConsultTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.GetPublicProfile;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdGenerator;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InactivateTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ListTenants;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ReactivateTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RegisterPendingTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place that knows the framework and the driver and wires the core to the adapters. The limits of the
 * connection are written here with their values (Annex C; plan D-09): a pool of 10, 5 s to get a connection
 * from it, 5 s to connect and to find a server, and 5 s without bytes from the server. That last one is not a
 * timeout of the statement: the server keeps running what it was asked and a write that timed out may have
 * been committed, which is why a creation is repeatable by its key. An operation timeout of the driver would
 * be the real one and is left for when the driver version of the project supports it in transactions.
 */
@Configuration
class CompositionRoot {

  @Bean
  ConnectionString mongoConnectionString(@Value("${MONGODB_URI:}") String uri) {
    if (uri.isBlank()) {
      throw new IllegalStateException("MONGODB_URI is required: the address of the database, with its name");
    }
    ConnectionString connection;
    try {
      connection = new ConnectionString(uri);
    } catch (IllegalArgumentException malformed) {
      // no cause on purpose: the message of the driver may quote the address, and the address has a password
      throw new IllegalStateException("MONGODB_URI is not a valid MongoDB connection string");
    }
    if (connection.getDatabase() == null) {
      throw new IllegalStateException("MONGODB_URI must name the database: mongodb://user:password@host/identity_audit");
    }
    return connection;
  }

  @Bean
  MongoClientSettings mongoClientSettings(ConnectionString connection) {
    return MongoClientSettings.builder()
        .applyConnectionString(connection)
        .applyToConnectionPoolSettings(pool -> pool.maxSize(10).maxWaitTime(5, TimeUnit.SECONDS))
        .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS))
        .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
        .build();
  }

  @Bean(destroyMethod = "close")
  MongoClient mongoClient(MongoClientSettings settings) {
    return MongoClients.create(settings);
  }

  @Bean
  TenantRepository tenantRepository(MongoClient client, ConnectionString connection) {
    return new MongoTenantRepository(client, connection.getDatabase());
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  IdGenerator idGenerator() {
    return () -> UUID.randomUUID().toString();
  }

  @Bean
  RegisterPendingTenant registerPendingTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    return new RegisterPendingTenant(tenants, ids, clock);
  }

  @Bean
  CompleteOnboarding completeOnboarding(TenantRepository tenants, IdGenerator ids, Clock clock) {
    return new CompleteOnboarding(tenants, ids, clock);
  }

  @Bean
  FailOnboarding failOnboarding(TenantRepository tenants, Clock clock) {
    return new FailOnboarding(tenants, clock);
  }

  @Bean
  InactivateTenant inactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    return new InactivateTenant(tenants, ids, clock);
  }

  @Bean
  ReactivateTenant reactivateTenant(TenantRepository tenants, IdGenerator ids, Clock clock) {
    return new ReactivateTenant(tenants, ids, clock);
  }

  @Bean
  ListTenants listTenants(TenantRepository tenants) {
    return new ListTenants(tenants);
  }

  @Bean
  ConsultTenant consultTenant(TenantRepository tenants) {
    return new ConsultTenant(tenants);
  }

  @Bean
  GetPublicProfile getPublicProfile(TenantRepository tenants) {
    return new GetPublicProfile(tenants);
  }
}
