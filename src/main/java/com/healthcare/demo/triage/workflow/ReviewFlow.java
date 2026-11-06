package com.healthcare.demo.triage.workflow;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read this class first to follow the complete demo. Each method names one step. */
@Service
public class ReviewFlow {
  private final ReviewStore store;
  private final CapabilityTokens permissions;
  private final A2aClient agents;
  private final ModelAssessment model;
  private final String toolsUrl;
  private final A2aClient.Agent pharmacist, guidance, care;
  private final ExecutorService jobs =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
  private final ExecutorService consultations = Executors.newFixedThreadPool(4);
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();

  public ReviewFlow(
      ReviewStore store,
      CapabilityTokens permissions,
      ChatClient.Builder builder,
      @Value("${demo.model}") String modelName,
      @Value("${demo.mcp-url}") String toolsUrl,
      @Value("${demo.pharmacist-url}") String pharmacistUrl,
      @Value("${demo.guidance-url}") String guidanceUrl,
      @Value("${demo.care-url}") String careUrl) {
    this.store = store;
    this.permissions = permissions;
    this.toolsUrl = toolsUrl;
    this.agents = new A2aClient(permissions);
    this.model = new ModelAssessment(builder, modelName);
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
    store.interruptUnfinished();
  }

  public void start(Run run) {
    if (!store.claim(run.id())) return;
    try {
      jobs.submit(() -> execute(run));
    } catch (RejectedExecutionException e) {
      store.finish(
          run.id(),
          new Result(
              Readiness.INCOMPLETE,
              run.input().proposedTreatment(),
              List.of(),
              List.of(),
              List.of(),
              "The demo is busy. Retry when another review finishes.",
              0));
    }
  }

  public List<PatientSummary> patients(String actor) {
    String token = permissions.issue(actor, "catalog", "*", 0, "mcp", List.of("listDemoPatients"));
    try (var tools = new McpAccess(toolsUrl, token, new ActivityLog(events -> {}))) {
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
    try (var tools = new McpAccess(toolsUrl, token, new ActivityLog(events -> {}))) {
      return tools.call(
          "getPatientSnapshot", Map.of("patientId", patientId), PatientSnapshot.class);
    }
  }

  private void execute(Run initial) {
    long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(90);
    var reviews = new ArrayList<SpecialistResult>();
    var timeout =
        deadlines.schedule(
            () -> {
              if (store.active(initial.id()))
                store.finish(
                    initial.id(),
                    new Result(
                        Readiness.INCOMPLETE,
                        initial.input().proposedTreatment(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "Review exceeded its 90-second deadline. Inspect service status and retry.",
                        90_000));
            },
            90,
            TimeUnit.SECONDS);
    try {
      var input = loadPatient(initial);
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
      publishAssessment(initial, safety, "Pharmacist finding received");
      var advice = guidanceFuture.join();
      reviews.add(advice);
      publishAssessment(initial, advice, "Guidance evidence received");
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
          publishAssessment(initial, feasibility, "Care options received");
          if ("true".equals(feasibility.details().get("covered"))) break;
        }
        if (candidates.isEmpty() || revisions >= 2) break;
        String candidate = candidates.removeFirst();
        revisions++;
        input = store.selectCandidate(initial.actor(), initial.id(), candidate);
        selected = candidate;
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
        publishAssessment(initial, safety, "Revised candidate safety review received");
        store.progress(initial.id(), partial(selected, reviews, safety, started));
      }
      var readiness = ReviewDecision.decide(safety, advice, feasibility);
      var sources = uniqueEvidence(reviews);
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
        summary = reply.assessment().summary();
        reviews.add(
            new SpecialistResult(
                "coordinator",
                input.caseId(),
                input.patientId(),
                input.revision(),
                input.snapshotVersion(),
                summary,
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
          new Result(
              readiness,
              selected,
              List.copyOf(reviews),
              safety.findings(),
              sources,
              summary,
              elapsed(started)));
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
        store.finish(
            initial.id(),
            new Result(
                Readiness.INCOMPLETE,
                initial.input().proposedTreatment(),
                List.copyOf(reviews),
                List.of(),
                uniqueEvidence(reviews),
                "A required tool, specialist, evidence check or model response could not complete."
                    + " No approval is available. Check the step details and retry.",
                elapsed(started)));
      }
    } finally {
      timeout.cancel(false);
    }
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
    try (var tools = new McpAccess(toolsUrl, token, activityLog(run, "coordinator"))) {
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
        Map.of("agent", agent.name(), "revision", input.revision()),
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
                      input.revision()),
                  event.durationMs());
            },
            () -> store.active(run.id()));
    store.event(
        run.id(),
        "A2A",
        "Consultation completed",
        Map.of("agent", agent.name(), "revision", input.revision()),
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

  private void publishAssessment(Run run, SpecialistResult result, String title) {
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
            result.revision()),
        result.modelUsage() == null ? 0 : result.modelUsage().durationMs());
  }

  private Result partial(
      String selected, List<SpecialistResult> reviews, SpecialistResult safety, long started) {
    return new Result(
        Readiness.INCOMPLETE,
        selected,
        List.copyOf(reviews),
        safety.findings(),
        uniqueEvidence(reviews),
        "Review is still running",
        elapsed(started));
  }

  private List<Evidence> uniqueEvidence(List<SpecialistResult> reviews) {
    var evidence = new LinkedHashMap<String, Evidence>();
    for (var r : reviews) for (var e : r.evidence()) evidence.putIfAbsent(e.id(), e);
    return List.copyOf(evidence.values());
  }

  private void requireActive(String id, long deadline) {
    if (!store.active(id) || System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
      throw new CancellationException("Review canceled or expired");
  }

  private long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  @PreDestroy
  public void stop() {
    jobs.shutdownNow();
    consultations.shutdownNow();
    deadlines.shutdownNow();
  }
}
