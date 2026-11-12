package com.ticketdrop.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ticketdrop.event.EventNotFoundException;

/**
 * Turns every failure into the API's error format: {@code {"error": CODE, "message": text}}, plus
 * any extra field the code calls for. Spring's own handling of things like an unknown route or an
 * unsupported method is inherited unchanged from the base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<Object> eventNotFound(EventNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "There is no event " + e.eventId() + ".");
    }

    /** Anything not handled above or by Spring. The details go to the log, never to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("Unexpected failure while handling a request", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side.");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "The request body is missing or is not valid JSON for this request.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String problems = ex.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + " " + field.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", problems);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "A value in the request address has the wrong type.");
    }

    static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return error(status, code, message, Map.of());
    }

    static ResponseEntity<Object> error(HttpStatus status, String code, String message, Map<String, ?> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.putAll(extra);
        return ResponseEntity.status(status).body(body);
    }
}
