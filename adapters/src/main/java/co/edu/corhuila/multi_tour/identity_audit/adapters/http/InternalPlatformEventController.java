package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.RecordPlatformEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What job 7 delivers for an account that belongs to no tenant, the Platform Administrator's (ADR-004 D4). It refuses an
 * event that carries a tenant, as the route of a tenant refuses one that carries none, so no event is recorded under
 * another tenant, or under none, by mistake. The role SERVICE is judged before this runs.
 */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/events")
class InternalPlatformEventController {

  private final ApplySessionRevocation apply;
  private final RecordPlatformEvent record;

  InternalPlatformEventController(ApplySessionRevocation apply, RecordPlatformEvent record) {
    this.apply = apply;
    this.record = record;
  }

  @PostMapping
  ResponseEntity<Void> receive(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody PlatformEventEnvelope body) {
    if (body.tenantId() != null) {
      throw new InvalidDataException("tenantId", "must be absent: an event with a tenant goes to the route of its tenant");
    }
    return body.receive(key, apply, record);
  }
}
