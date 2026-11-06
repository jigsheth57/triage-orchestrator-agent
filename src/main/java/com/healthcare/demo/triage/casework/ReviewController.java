package com.healthcare.demo.triage.casework;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import com.healthcare.demo.triage.workflow.ReviewFlow;
import jakarta.validation.Valid;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** HTTP endpoints stay thin. ReviewFlow explains the work; ReviewStore protects durable state. */
@RestController
public class ReviewController {
  private final ReviewStore store;
  private final ReviewFlow flow;
  private final String model;
  private final ExecutorService streams;

  public ReviewController(
      ReviewStore store,
      ReviewFlow flow,
      @Value("${demo.model}") String model,
      @org.springframework.beans.factory.annotation.Qualifier("reviewStreams")
          ExecutorService streams) {
    this.store = store;
    this.flow = flow;
    this.model = model;
    this.streams = streams;
  }

  @GetMapping("/api/session")
  public Map<String, Object> session(Authentication auth, CsrfToken csrf) {
    boolean signedIn = auth != null && !(auth instanceof AnonymousAuthenticationToken);
    return Map.of(
        "authenticated",
        signedIn,
        "username",
        signedIn ? auth.getName() : "",
        "canApprove",
        signedIn
            && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CLINICIAN")),
        "csrfToken",
        csrf.getToken(),
        "csrfHeader",
        csrf.getHeaderName());
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("service", "triage-orchestrator-agent", "status", "running");
  }

  @GetMapping("/api/runtime")
  public Map<String, String> runtime() {
    return Map.of(
        "mode",
        "LIVE",
        "model",
        model,
        "data",
        "Synthetic demonstration records only",
        "scope",
        "Selected allergy and medication-interaction fixtures; no dose or diagnosis validation");
  }

  @GetMapping("/api/patients")
  public List<PatientSummary> patients(Authentication auth) {
    return flow.patients(auth.getName());
  }

  @GetMapping("/api/patients/{id}")
  public PatientSnapshot patient(Authentication auth, @PathVariable String id) {
    return flow.patient(auth.getName(), id);
  }

  @PostMapping("/api/reviews")
  @PreAuthorize("hasRole('CLINICIAN')")
  public ResponseEntity<Run> start(Authentication auth, @Valid @RequestBody StartRequest request) {
    var run = store.create(auth.getName(), request);
    if (run.state().equals("QUEUED")) flow.start(run);
    return ResponseEntity.accepted().body(store.get(auth.getName(), run.id()));
  }

  @GetMapping("/api/reviews")
  public List<Map<String, Object>> recent(Authentication auth) {
    return store.recent(auth.getName());
  }

  @GetMapping("/api/reviews/{id}")
  public Run get(Authentication auth, @PathVariable String id) {
    return store.get(auth.getName(), id);
  }

  @PostMapping("/api/reviews/{id}/cancel")
  @PreAuthorize("hasRole('CLINICIAN')")
  public Run cancel(Authentication auth, @PathVariable String id) {
    store.cancel(auth.getName(), id);
    return store.get(auth.getName(), id);
  }

  @PostMapping("/api/reviews/{id}/approvals")
  @PreAuthorize("hasRole('CLINICIAN')")
  public Run approve(
      Authentication auth, @PathVariable String id, @Valid @RequestBody ApprovalRequest request) {
    store.approve(auth.getName(), id, request);
    return store.get(auth.getName(), id);
  }

  @GetMapping("/api/cases/{id}")
  public Map<String, Object> history(Authentication auth, @PathVariable String id) {
    return store.caseHistory(auth.getName(), id);
  }

  @PostMapping("/api/cases/{id}/changes")
  @PreAuthorize("hasRole('CLINICIAN')")
  public Map<String, Object> change(
      Authentication auth, @PathVariable String id, @Valid @RequestBody ChangeRequest request) {
    store.addReport(auth.getName(), id, request);
    return store.caseHistory(auth.getName(), id);
  }

  @GetMapping(value = "/api/reviews/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      Authentication auth,
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") String after,
      @RequestParam(defaultValue = "0") long cursor) {
    String actor = auth.getName();
    store.get(actor, id);
    long start = Math.max(cursor, Long.parseLong(after));
    var emitter = new SseEmitter(0L);
    var open = new AtomicBoolean(true);
    emitter.onCompletion(() -> open.set(false));
    emitter.onTimeout(() -> open.set(false));
    emitter.onError(e -> open.set(false));
    try {
      streams.submit(() -> stream(actor, id, start, emitter, open));
    } catch (RejectedExecutionException e) {
      emitter.completeWithError(new IllegalStateException("Too many event streams"));
    }
    return emitter;
  }

  private void stream(
      String actor, String id, long cursor, SseEmitter emitter, AtomicBoolean open) {
    try {
      int ticks = 0;
      while (open.get()) {
        for (var event : store.events(actor, id, cursor)) {
          emitter.send(
              SseEmitter.event().id(String.valueOf(event.id())).name("activity").data(event));
          cursor = event.id();
        }
        var run = store.get(actor, id);
        emitter.send(SseEmitter.event().name("review").data(run));
        if (!Set.of("QUEUED", "RUNNING").contains(run.state())) {
          // Read again after seeing terminal state: completion can race the first event query.
          for (var event : store.events(actor, id, cursor)) {
            emitter.send(
                SseEmitter.event().id(String.valueOf(event.id())).name("activity").data(event));
            cursor = event.id();
          }
          emitter.complete();
          return;
        }
        if (++ticks % 20 == 0) emitter.send(SseEmitter.event().comment("keepalive"));
        Thread.sleep(300);
      }
    } catch (Exception e) {
      open.set(false);
      emitter.completeWithError(e);
    }
  }

  @PostMapping("/api/triage/evaluate")
  public ResponseEntity<String> legacy() {
    return ResponseEntity.status(410)
        .contentType(MediaType.TEXT_PLAIN)
        .body(
            "This demo now uses the live /api/reviews workflow. See README.md for the new"
                + " contract.");
  }
}
