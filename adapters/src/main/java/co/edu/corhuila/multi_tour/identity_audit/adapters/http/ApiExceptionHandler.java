package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

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

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    return handleExceptionInternal(
        e, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }
}
