package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.session.ApplySessionRevocation;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What job 7 delivers about the sessions of an account; the role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants/{tenantId}/events")
class InternalSessionEventController {

  private final ApplySessionRevocation apply;

  InternalSessionEventController(ApplySessionRevocation apply) {
    this.apply = apply;
  }

  /** Texts, not anything else. The other routes refuse a field they do not name; this one tolerates it, because a publisher may add some (ADR-004 D4). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Envelope(String eventId, String eventType, String tenantId, String aggregateId, Payload payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Payload(String accountId, String revokedFrom) {}

  @PostMapping
  ResponseEntity<Void> receive(
      @PathVariable String tenantId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Envelope body) {
    if (key == null || !key.equals(body.eventId())) {
      throw new InvalidDataException("Idempotency-Key", "must be the id of the event");
    }
    if (body.payload() == null) {
      throw new InvalidDataException("payload", "is required");
    }
    if (!tenantId.equals(body.tenantId())) {
      throw new InvalidDataException("tenantId", "must be the tenant of the path");
    }
    ApplySessionRevocation.Outcome outcome = apply.execute(new ApplySessionRevocation.Command(
        body.eventId(), body.eventType(), body.tenantId(), body.aggregateId(), body.payload().accountId(), body.payload().revokedFrom()));
    return ResponseEntity.status(outcome == ApplySessionRevocation.Outcome.APPLIED ? HttpStatus.CREATED : HttpStatus.OK).build();
  }
}
