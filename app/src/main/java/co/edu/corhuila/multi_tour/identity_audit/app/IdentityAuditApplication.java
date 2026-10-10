package co.edu.corhuila.multi_tour.identity_audit.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The composition root: the only place that knows the framework and wires the modules together. */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.multi_tour.identity_audit")
public class IdentityAuditApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityAuditApplication.class, args);
  }
}
