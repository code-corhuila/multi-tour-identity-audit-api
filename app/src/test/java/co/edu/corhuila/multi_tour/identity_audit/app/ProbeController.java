package co.edu.corhuila.multi_tour.identity_audit.app;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdentifierTakenException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidStatusTransitionException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantNotFoundException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Routes that exist only in the tests, to make the shell fail in the ways a real route will. */
@RestController
class ProbeController {

  @PostMapping(path = "/probe/echo", consumes = "application/json")
  Map<String, Object> echo(@RequestBody Map<String, Object> body) {
    return body;
  }

  @GetMapping("/probe/number")
  Map<String, Integer> number(@RequestParam int n) {
    return Map.of("n", n);
  }

  @GetMapping("/probe/boom")
  void boom() {
    throw new IllegalStateException("mongodb://identity_audit_app:secret@mongo:27017");
  }

  /** A failure of the core, by name, so that its translation to HTTP is tried once for every route to come. */
  @GetMapping("/probe/domain/{kind}")
  void domain(@PathVariable String kind) {
    switch (kind) {
      case "invalid-data" -> throw new InvalidDataException("The identifier is 3 to 40 lowercase letters");
      case "not-found" -> throw new TenantNotFoundException("00000000-0000-4000-8000-0000000000ff");
      case "identifier-taken" -> throw new IdentifierTakenException("acme-tours");
      case "invalid-transition" -> throw new InvalidStatusTransitionException(TenantStatus.ACTIVE, "be reactivated");
      case "concurrent" -> throw new ConcurrentChangeException("00000000-0000-4000-8000-000000000001");
      case "plain-illegal-argument" -> throw new IllegalArgumentException("mongodb://user:secret@host from a library");
      default -> throw new IllegalStateException(kind);
    }
  }
}
