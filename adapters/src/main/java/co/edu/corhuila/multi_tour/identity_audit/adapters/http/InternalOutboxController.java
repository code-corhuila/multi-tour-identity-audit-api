package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.outbox.ListDueEvents;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.MarkEventPublished;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxEvent;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What job 7 asks of the outbox; the role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/outbox")
class InternalOutboxController {

  private static final Set<String> FILTERS = Set.of("page", "limit", "status");

  private final ListDueEvents list;
  private final MarkEventPublished publish;

  InternalOutboxController(ListDueEvents list, MarkEventPublished publish) {
    this.list = list;
    this.publish = publish;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EventResponse(
      String eventId, String eventType, int eventVersion, String tenantId, String aggregateId, String actorId,
      String reason, Map<String, Object> payload, String correlationId, Instant occurredAt, Instant publishedAt,
      String status, int attempts, String lastError, Instant nextAttemptAt) {

    static EventResponse of(OutboxEvent e) {
      return new EventResponse(
          e.eventId(), e.eventType(), e.eventVersion(), e.tenantId(), e.aggregateId(), e.actorId(), e.reason(),
          e.payload(), e.correlationId(), e.occurredAt(), e.publishedAt(), e.status().name(), e.attempts(),
          e.lastError(), e.nextAttemptAt());
    }
  }

  public record EventPage(List<EventResponse> data, Paging.Meta meta) {}

  @GetMapping
  EventPage list(HttpServletRequest request) {
    Paging.onlyDeclared(request, FILTERS);
    // job 7 reads only what is waiting; the status is in the contract so that it stays explicit
    String status = request.getParameter("status");
    if (status != null && !status.equals("PENDING")) {
      throw new InvalidDataException("Only the PENDING events are read");
    }
    Page<OutboxEvent> found = list.execute(new ListDueEvents.Query(
        Paging.number(request.getParameter("page"), 1), Paging.number(request.getParameter("limit"), 20)));
    return new EventPage(found.data().stream().map(EventResponse::of).toList(), Paging.Meta.of(found));
  }

  @PostMapping("/{eventId}/published")
  EventResponse published(@PathVariable String eventId) {
    return EventResponse.of(publish.execute(eventId));
  }
}
