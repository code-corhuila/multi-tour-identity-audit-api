package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.audit.RecordPlatformEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What job 7 delivers for a tenant: every event becomes an audit record, and a session revocation also moves the cutoff of its account. The role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants/{tenantId}/events")
class InternalSessionEventController {

  private final ApplySessionRevocation apply;
  private final RecordPlatformEvent record;

  InternalSessionEventController(ApplySessionRevocation apply, RecordPlatformEvent record) {
    this.apply = apply;
    this.record = record;
  }

  @PostMapping
  ResponseEntity<Void> receive(
      @PathVariable String tenantId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody PlatformEventEnvelope body) {
    if (!tenantId.equals(body.tenantId())) {
      throw new InvalidDataException("tenantId", "must be the tenant of the path");
    }
    return body.receive(key, apply, record);
  }
}
