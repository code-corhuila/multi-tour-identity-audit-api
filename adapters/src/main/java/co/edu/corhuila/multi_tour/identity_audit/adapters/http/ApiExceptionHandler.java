package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.ForbiddenException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.ConcurrentChangeException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.IdentifierTakenException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidStatusTransitionException;
import co.edu.corhuila.multi_tour.identity_audit.core.outbox.OutboxEventNotFoundException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns a failure inside MVC into a status and the envelope. Every exception that
 * Spring already knows how to rank (bad parameter 400, wrong method 405, media types 415 and 406, no
 * route 404) keeps its own status; anything else is a 500 whose detail goes to the log, never to the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (status.is5xxServerError()) {
      LOG.error("Unexpected failure", e);
    }
    HttpHeaders answer = new HttpHeaders();
    answer.putAll(headers);
    answer.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(ErrorBody.of(status, CorrelationIdFilter.current()), answer, status);
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<Object> forbidden(ForbiddenException e) {
    return answer(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), null);
  }

  /** What the core refuses is told to the caller in the words the core wrote for it. */
  @ExceptionHandler(InvalidDataException.class)
  ResponseEntity<Object> invalidData(InvalidDataException e) {
    return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage(), null);
  }

  @ExceptionHandler(TenantNotFoundException.class)
  ResponseEntity<Object> notFound(TenantNotFoundException e) {
    return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(OutboxEventNotFoundException.class)
  ResponseEntity<Object> eventNotFound(OutboxEventNotFoundException e) {
    return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(IdentifierTakenException.class)
  ResponseEntity<Object> identifierTaken(IdentifierTakenException e) {
    return answer(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", e.getMessage(),
        List.of(new ErrorBody.Detail("identifier", e.getMessage())));
  }

  @ExceptionHandler(InvalidStatusTransitionException.class)
  ResponseEntity<Object> invalidTransition(InvalidStatusTransitionException e) {
    return answer(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS_TRANSITION", e.getMessage(), null);
  }

  /** Not a transition and not a fault of the caller: another request got there first, and a retry decides again. */
  @ExceptionHandler(ConcurrentChangeException.class)
  ResponseEntity<Object> concurrentChange(ConcurrentChangeException e) {
    LOG.warn("A change raced with another: {}", e.getMessage());
    return answer(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
        "The data changed while the request was being decided; try again", null);
  }

  private ResponseEntity<Object> answer(HttpStatus status, String code, String message, List<ErrorBody.Detail> details) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(new ErrorBody(code, message, details, CorrelationIdFilter.current()), headers, status);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    return handleExceptionInternal(
        e, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }
}
