package com.healthcare.demo.triage.casework;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ReviewModels.Conflict.class)
  public ResponseEntity<?> conflict(RuntimeException e) {
    return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<?> denied() {
    return ResponseEntity.status(403)
        .body(Map.of("error", "This case or patient is not available to the current user"));
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  public ResponseEntity<?> invalid() {
    return ResponseEntity.badRequest()
        .body(Map.of("error", "Check the patient, diagnosis, treatment and version fields"));
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  public ResponseEntity<?> unavailable() {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "error", "A required service is unavailable. Check the local services and retry."));
  }
}
