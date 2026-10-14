package co.edu.corhuila.multi_tour.identity_audit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CompleteOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConsultTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.GetPublicProfile;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InactivateTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ListTenants;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ReactivateTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.RegisterPendingTenant;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantRepository;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import com.mongodb.MongoClientSettings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

/** The composition root: the only place that knows the framework and the driver and wires the core to them. */
class MongoCompositionRootTest extends ShellTest {

  @Autowired ApplicationContext context;
  @Autowired MongoClientSettings settings;

  @Test
  void C_every_use_case_and_the_repository_are_beans() {
    for (Class<?> type : List.of(RegisterPendingTenant.class, CompleteOnboarding.class, FailOnboarding.class,
        InactivateTenant.class, ReactivateTenant.class, ListTenants.class, ConsultTenant.class,
        GetPublicProfile.class, TenantRepository.class)) {
      assertThat(context.getBeansOfType(type)).as(type.getSimpleName()).hasSize(1);
    }
  }

  @Test
  void C_the_connection_limits_are_declared_and_not_left_to_the_driver() {
    assertThat(settings.getConnectionPoolSettings().getMaxSize()).isEqualTo(10);
    assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
    assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
    // with the database down a request waits 5 s at most, not the 30 s and 10 s that the driver would wait
    assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
    assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
  }

  private Throwable startWith(String uri) {
    List<String> args = new ArrayList<>(List.of("--server.port=0", "--MONGODB_URI=" + uri,
        "--JWT_PUBLIC_KEY=" + Tokens.publicKeyPem()));
    return catchThrowable(() -> SpringApplication.run(IdentityAuditApplication.class, args.toArray(String[]::new)));
  }

  @Test
  void C_the_service_does_not_start_without_the_address_of_the_database_or_without_its_name() {
    assertThat(startWith("")).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("MONGODB_URI");
    assertThat(startWith("mongodb://localhost:27017")).rootCause().isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("name the database");
  }

  @Test
  void C_a_malformed_address_stops_the_service_without_writing_the_password_anywhere() {
    Throwable failure = startWith("mongodb://identity_audit_app:s3cretPass@host:notaport/identity_audit");
    assertThat(failure).isNotNull();
    StringWriter trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));
    assertThat(trace.toString()).contains("MONGODB_URI").doesNotContain("s3cretPass");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
  void I_the_wired_service_registers_a_tenant_in_the_real_database(
      @Autowired RegisterPendingTenant register, @Autowired ConsultTenant consult) {
    String unique = Long.toString(Math.abs(System.nanoTime()));
    var result = register.execute(new RegisterPendingTenant.Command(
        "wired-" + unique, "Wired Tours", "America/Bogota", "CO", "wired-key-" + unique));
    assertThat(result.created()).isTrue();
    assertThat(consult.execute(result.tenant().tenantId()).status()).isEqualTo(TenantStatus.PENDING);
  }
}
