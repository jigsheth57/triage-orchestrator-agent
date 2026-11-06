package com.healthcare.demo.triage.workflow;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read this class first to follow the complete demo. Each method names one step. */
@Service
public class ReviewFlow {
  private final ReviewStore store;
  private final CapabilityTokens permissions;
  private final A2aClient agents;
  private final ModelAssessment model;
  private final McpAccessFactory toolsFactory;
  private final String toolsUrl;
  private final A2aClient.Agent pharmacist, guidance, care;
  private final ExecutorService jobs;
  private final ExecutorService consultations;
  private final ScheduledExecutorService deadlines;

  public ReviewFlow(
      ReviewStore store,
      CapabilityTokens permissions,
      A2aClient agents,
      ModelAssessment model,
      McpAccessFactory toolsFactory,
      @Qualifier("reviewJobs") ExecutorService jobs,
      @Qualifier("reviewConsultations") ExecutorService consultations,
      @Qualifier("reviewDeadlines") ScheduledExecutorService deadlines,
      @Value("${demo.mcp-url}") String toolsUrl,
      @Value("${demo.pharmacist-url}") String pharmacistUrl,
      @Value("${demo.guidance-url}") String guidanceUrl,
      @Value("${demo.care-url}") String careUrl) {
    this.store = store;
    this.permissions = permissions;
    this.toolsUrl = toolsUrl;
    this.toolsFactory = toolsFactory;
    this.agents = agents;
    this.model = model;
    this.jobs = jobs;
    this.consultations = consultations;
    this.deadlines = deadlines;
    deadlines.scheduleWithFixedDelay(
        () -> {
          try {
            store.maintainLeases();
          } catch (org.springframework.dao.DataAccessException unavailable) {
            /* Lease expiry fences work during outages. */
          }
        },
        0,
        10,
        TimeUnit.SECONDS);
    pharmacist =
        new A2aClient.Agent(
            "pharmacist",
            pharmacistUrl,
            "medication-safety",
            List.of("getPatientSnapshot", "evaluateMedicationSafety"));
    guidance =
        new A2aClient.Agent(
            "guidance", guidanceUrl, "clinical-guidance", List.of("searchPolicies"));
    care = new A2aClient.Agent("care", careUrl, "care-feasibility", List.of("getCareOptions"));
  }

  public void start(Run run) {
    if (!store.claim(run.id())) return;
    var timeout =
        deadlines.schedule(
            () -> expire(run),
            Math.max(
                0,
                90_000
                    - java.time.Duration.between(run.createdAt(), java.time.Instant.now())
                        .toMillis()),
            TimeUnit.MILLISECONDS);
    try {
      jobs.submit(
          () -> {
            try {
              execute(run);
            } finally {
              timeout.cancel(false);
            }
          });
    } catch (RejectedExecutionException e) {
      timeout.cancel(false);
      var empty =
          OutcomePresentation.result(
              run.input(), List.of(run.input()), List.of(), Readiness.INCOMPLETE, 0);
      store.finish(
          run.id(),
          new Result(
              empty.readiness(),
              empty.selectedTreatment(),
              empty.reviews(),
              empty.findings(),
              empty.evidence(),
              "The demo is busy. Retry when another review finishes.",
              0,
              empty.originalTreatment(),
              empty.attempts(),
              empty.observations(),
              "Retry after another review finishes. Approval is unavailable."));
    }
  }

  public List<PatientSummary> patients(String actor) {
    String token = permissions.issue(actor, "catalog", "*", 0, "mcp", List.of("listDemoPatients"));
    try (var tools = toolsFactory.open(toolsUrl, token, new ActivityLog(events -> {}))) {
      var all = tools.call("listDemoPatients", Map.of(), PatientSummary[].class);
      return Arrays.stream(all)
          .filter(
              p -> {
                try {
                  store.requireGrant(actor, p.patientId());
                  return true;
                } catch (SecurityException e) {
                  return false;
                }
              })
          .toList();
    }
  }

  public PatientSnapshot patient(String actor, String patientId) {
    store.requireGrant(actor, patientId);
    String token =
        permissions.issue(
            actor, "chart-preview", patientId, 0, "mcp", List.of("getPatientSnapshot"));
    try (var tools = toolsFactory.open(toolsUrl, token, new ActivityLog(events -> {}))) {
      return tools.call(
          "getPatientSnapshot", Map.of("patientId", patientId), PatientSnapshot.class);
    }
  }

  private void execute(Run initial) {
    long started = System.nanoTime();
    long remainingMs =
        Math.max(
            0,
            90_000
                - java.time.Duration.between(initial.createdAt(), java.time.Instant.now())
                    .toMillis());
    long deadline = started + TimeUnit.MILLISECONDS.toNanos(remainingMs);
    var reviews = new ArrayList<SpecialistResult>();
    var inputs = new ArrayList<ReviewInput>();
    try {
      requireActive(initial.id(), deadline);
      var input = loadPatient(initial);
      inputs.add(input);
      store.progress(
          initial.id(),
          OutcomePresentation.result(
              initial.input(), inputs, reviews, Readiness.INCOMPLETE, elapsed(started)));
      store.stage(initial.id(), "Pharmacist and guidance reviews");
      var firstInput = input;
      var safetyFuture =
          CompletableFuture.supplyAsync(
              () -> consult(initial, firstInput, pharmacist, deadline), consultations);
      var guidanceFuture =
          CompletableFuture.supplyAsync(
              () -> consult(initial, firstInput, guidance, deadline), consultations);
      var safety = safetyFuture.join();
      reviews.add(safety);
      publishAssessment(initial, firstInput, safety, "Pharmacist finding received");
      var advice = guidanceFuture.join();
      reviews.add(advice);
      publishAssessment(initial, firstInput, advice, "Guidance evidence received");
      store.progress(
          initial.id(),
          OutcomePresentation.result(
              initial.input(), inputs, reviews, Readiness.INCOMPLETE, elapsed(started)));
      String selected = input.proposedTreatment();
      SpecialistResult feasibility = null;
      String originalDrug = safety.details().get("drug");
      var candidates =
          new ArrayDeque<>(
              advice.candidates().stream()
                  .filter(c -> !c.equals(originalDrug))
                  .distinct()
                  .limit(2)
                  .toList());
      int revisions = 0;
      while (true) {
        requireActive(initial.id(), deadline);
        if (!"true".equals(safety.details().get("supported"))) break;
        boolean blocked = safety.findings().stream().anyMatch(f -> f.severity().equals("BLOCKING"));
        if (!blocked) {
          store.stage(initial.id(), "Coverage and follow-up review");
          feasibility = consult(initial, input, care, deadline);
          reviews.add(feasibility);
          publishAssessment(initial, input, feasibility, "Care options received");
          if ("true".equals(feasibility.details().get("covered"))) break;
        }
        if (candidates.isEmpty() || revisions >= 2) break;
        String candidate = candidates.removeFirst();
        revisions++;
        input = store.selectCandidate(initial.actor(), initial.id(), candidate);
        selected = candidate;
        inputs.add(input);
        store.progress(
            initial.id(),
            OutcomePresentation.result(
                initial.input(), inputs, reviews, Readiness.INCOMPLETE, elapsed(started)));
        store.event(
            initial.id(),
            "HANDOFF",
            "Revised candidate sent for a fresh safety check",
            Map.of("candidate", candidate, "revision", input.revision()),
            0);
        store.stage(initial.id(), "Rechecking revised treatment");
        safety = consult(initial, input, pharmacist, deadline);
        reviews.add(safety);
        feasibility = null;
        publishAssessment(initial, input, safety, "Revised candidate safety review received");
        store.progress(
            initial.id(),
            OutcomePresentation.result(
                initial.input(), inputs, reviews, Readiness.INCOMPLETE, elapsed(started)));
      }
      var readiness = ReviewDecision.decide(safety, advice, feasibility);
      var sources =
          OutcomePresentation.result(initial.input(), inputs, reviews, readiness, elapsed(started))
              .evidence();
      String summary =
          "Required demonstration checks are "
              + readiness.name().toLowerCase(Locale.ROOT).replace('_', ' ')
              + ". The clinician must review the draft and its scope.";
      if (!sources.isEmpty() && readiness != Readiness.INCOMPLETE) {
        store.stage(initial.id(), "Preparing the source-linked summary");
        var log = activityLog(initial, "coordinator");
        var reply =
            model.assess(
                "coordinator",
                Map.of(
                    "readiness",
                    readiness,
                    "selectedTreatment",
                    selected,
                    "assessments",
                    reviews.stream()
                        .map(
                            r ->
                                Map.of(
                                    "agent",
                                    r.agent(),
                                    "summary",
                                    r.summary(),
                                    "revision",
                                    r.revision()))
                        .toList(),
                    "evidence",
                    sources),
                sources,
                log);
        requireActive(initial.id(), deadline);
        String commentary = reply.assessment().summary();
        reviews.add(
            new SpecialistResult(
                "coordinator",
                input.caseId(),
                input.patientId(),
                input.revision(),
                input.snapshotVersion(),
                commentary,
                List.of(),
                sources,
                List.of(),
                Map.of(),
                log.events(),
                reply.usage()));
      }
      requireActive(initial.id(), deadline);
      store.finish(
          initial.id(),
          OutcomePresentation.result(
              initial.input(), inputs, reviews, readiness, elapsed(started)));
    } catch (Exception e) {
      if (store.active(initial.id())) {
        store.event(
            initial.id(),
            "ERROR",
            "A required review step failed",
            Map.of(
                "reason",
                e instanceof CompletionException && e.getCause() != null
                    ? e.getCause().getClass().getSimpleName()
                    : e.getClass().getSimpleName()),
            0);
        if (inputs.isEmpty()) inputs.add(initial.input());
        var failure =
            OutcomePresentation.result(
                initial.input(), inputs, reviews, Readiness.INCOMPLETE, elapsed(started));
        store.finish(initial.id(), failure);
      }
    }
  }

  private void expire(Run initial) {
    if (!store.active(initial.id())) return;
    var current = store.get(initial.actor(), initial.id());
    var partial = current.result();
    if (partial == null) {
      partial =
          OutcomePresentation.result(
              initial.input(), List.of(initial.input()), List.of(), Readiness.INCOMPLETE, 90_000);
    }
    String action =
        "The 90-second review deadline expired, including queue wait. Start a fresh review after"
            + " checking service status. Approval is unavailable.";
    var attempts =
        partial.attempts().stream()
            .map(
                a ->
                    a.disposition().equals("SELECTED")
                        ? new ProposalAttempt(
                            a.revision(), a.treatment(), "INCOMPLETE", action, a.checks())
                        : a)
            .toList();
    store.finish(
        initial.id(),
        new Result(
            Readiness.INCOMPLETE,
            partial.selectedTreatment(),
            partial.reviews(),
            partial.findings(),
            partial.evidence(),
            partial.selectedTreatment() + ": " + action,
            90_000,
            partial.originalTreatment(),
            attempts,
            partial.observations(),
            action));
  }

  private ReviewInput loadPatient(Run run) {
    store.stage(run.id(), "Retrieving the patient record");
    String token =
        permissions.issue(
            run.actor(),
            run.caseId(),
            run.input().patientId(),
            run.revision(),
            "mcp",
            List.of("getPatientSnapshot"));
    try (var tools = toolsFactory.open(toolsUrl, token, activityLog(run, "coordinator"))) {
      var patient =
          tools.call(
              "getPatientSnapshot",
              Map.of("patientId", run.input().patientId()),
              PatientSnapshot.class);
      store.recordSnapshot(run.id(), patient.snapshotVersion());
      store.rememberPreference(run, patient.preference());
      store.event(
          run.id(),
          "RECORD",
          "Patient snapshot retrieved",
          Map.of("snapshotVersion", patient.snapshotVersion()),
          0);
      return store.currentInput(run.actor(), run.id(), run.input().proposedTreatment());
    }
  }

  private SpecialistResult consult(
      Run run, ReviewInput input, A2aClient.Agent agent, long deadline) {
    requireActive(run.id(), deadline);
    store.event(
        run.id(),
        "A2A",
        "Consulting " + agent.name(),
        Map.of(
            "agent",
            agent.name(),
            "revision",
            input.revision(),
            "candidate",
            input.proposedTreatment()),
        0);
    long started = System.nanoTime();
    var result =
        agents.consult(
            agent,
            input,
            run.actor(),
            deadline,
            event -> {
              store.event(
                  run.id(),
                  event.kind(),
                  event.message(),
                  Map.of(
                      "agent",
                      agent.name(),
                      "operation",
                      event.operation(),
                      "activityId",
                      event.id(),
                      "sourceTime",
                      event.at().toString(),
                      "revision",
                      input.revision(),
                      "candidate",
                      input.proposedTreatment()),
                  event.durationMs());
            },
            () -> store.active(run.id()));
    store.event(
        run.id(),
        "A2A",
        "Consultation completed",
        Map.of(
            "agent",
            agent.name(),
            "revision",
            input.revision(),
            "candidate",
            input.proposedTreatment()),
        elapsed(started));
    return result;
  }

  private ActivityLog activityLog(Run run, String agent) {
    Set<String> seen = new HashSet<>();
    return new ActivityLog(
        events -> {
          for (var e : events)
            if (seen.add(e.id()))
              store.event(
                  run.id(),
                  e.kind(),
                  e.message(),
                  Map.of("agent", agent, "operation", e.operation(), "activityId", e.id()),
                  e.durationMs());
        });
  }

  private void publishAssessment(
      Run run, ReviewInput input, SpecialistResult result, String title) {
    store.event(
        run.id(),
        "ASSESSMENT",
        title,
        Map.of(
            "agent",
            result.agent(),
            "summary",
            result.summary(),
            "findings",
            result.findings(),
            "evidence",
            result.evidence(),
            "revision",
            result.revision(),
            "candidate",
            input.proposedTreatment()),
        result.modelUsage() == null ? 0 : result.modelUsage().durationMs());
  }

  private List<Evidence> uniqueEvidence(List<SpecialistResult> reviews) {
    // Failure diagnostics preserve every distinct observation instead of collapsing by source ID.
    return reviews.stream().flatMap(r -> r.evidence().stream()).distinct().toList();
  }

  private void requireActive(String id, long deadline) {
    if (!store.active(id) || System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
      throw new CancellationException("Review canceled or expired");
  }

  private long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  @jakarta.annotation.PreDestroy
  public void releaseClaims() {
    store.releaseOwned();
  }
}
