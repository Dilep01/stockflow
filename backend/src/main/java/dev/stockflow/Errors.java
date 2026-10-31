package dev.stockflow;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ResponseEntity<?> upstream(Exception e) {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "error",
                "A required service is unavailable or the product could not be found; retry after checking the catalog"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("error", e.getReason() == null ? "Request failed" : e.getReason()));
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", "Invalid request; check required fields and quantities"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("error", "Operation conflicts with an existing record or stock constraint"));
  }
}
