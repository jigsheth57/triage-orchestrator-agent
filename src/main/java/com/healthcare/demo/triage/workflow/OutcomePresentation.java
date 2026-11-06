package com.healthcare.demo.triage.workflow;

import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Builds authoritative explanations from typed checks, never from generated narrative. */
public final class OutcomePresentation {
  private OutcomePresentation() {}

  public static Result result(
      ReviewInput original,
      List<ReviewInput> inputs,
      List<SpecialistResult> reviews,
      Readiness readiness,
      long durationMs) {
    var attempts = new ArrayList<ProposalAttempt>();
    var observations = new ArrayList<EvidenceObservation>();
    var selected = inputs.get(inputs.size() - 1);
    var guidance =
        reviews.stream().filter(r -> r.agent().equals("guidance")).findFirst().orElse(null);
    for (var input : inputs) {
      var safety = review(reviews, "pharmacist", input.revision());
      var care = review(reviews, "care", input.revision());
      var checks = new ArrayList<AttemptCheck>();
      checks.add(check(input, "SAFETY", safety, observations));
      checks.add(check(input, "GUIDANCE", guidance, observations));
      checks.add(check(input, "COVERAGE", care, observations));
      checks.add(check(input, "FOLLOWUP", care, observations));
      boolean current = input.revision() == selected.revision();
      // SELECTED identifies the current constrained draft, not an approval. A blocked current
      // request is rejected; its final position still identifies the latest evaluated attempt.
      var status =
          !current
              ? "REJECTED"
              : switch (readiness) {
                case BLOCKED -> "REJECTED";
                case INCOMPLETE -> "INCOMPLETE";
                case REVIEW_REQUIRED, READY_FOR_CLINICIAN_REVIEW -> "SELECTED";
              };
      attempts.add(
          new ProposalAttempt(
              input.revision(),
              input.proposedTreatment(),
              status,
              reason(safety, care, current, readiness),
              List.copyOf(checks)));
    }
    var currentSafety = review(reviews, "pharmacist", selected.revision());
    var currentCare = review(reviews, "care", selected.revision());
    // Source identity and complete content must both match to deduplicate a current passage.
    var evidence = new LinkedHashSet<Evidence>();
    for (var r : Arrays.asList(currentSafety, guidance, currentCare))
      if (r != null) evidence.addAll(r.evidence());
    String action =
        switch (readiness) {
          case READY_FOR_CLINICIAN_REVIEW ->
              "Review the selected draft and its current evidence, then explicitly attest before"
                  + " recording approval. No order is sent.";
          case BLOCKED ->
              "Resolve the blocking finding and start a fresh review. Approval is unavailable.";
          case REVIEW_REQUIRED ->
              "Resolve the remaining review or operational constraints and run fresh checks."
                  + " Approval is unavailable.";
          case INCOMPLETE ->
              "Complete the missing checks or retry after restoring the required service. Approval"
                  + " is unavailable.";
        };
    String summary =
        selected.proposedTreatment()
            + " is "
            + readiness.name().toLowerCase(Locale.ROOT).replace('_', ' ')
            + ". "
            + (inputs.size() > 1
                ? "The original request for "
                    + original.proposedTreatment()
                    + " was revised. Each candidate below shows its completed and missing checks. "
                : "")
            + reason(currentSafety, currentCare, true, readiness)
            + " "
            + action;
    return new Result(
        readiness,
        selected.proposedTreatment(),
        List.copyOf(reviews),
        currentSafety == null ? List.of() : currentSafety.findings(),
        List.copyOf(evidence),
        summary,
        durationMs,
        original.proposedTreatment(),
        List.copyOf(attempts),
        List.copyOf(observations),
        action);
  }

  private static SpecialistResult review(
      List<SpecialistResult> reviews, String agent, long revision) {
    return reviews.stream()
        .filter(r -> r.agent().equals(agent) && r.revision() == revision)
        .reduce((first, last) -> last)
        .orElse(null);
  }

  private static AttemptCheck check(
      ReviewInput input,
      String kind,
      SpecialistResult result,
      List<EvidenceObservation> observations) {
    if (result == null) return new AttemptCheck(kind, "NOT_RUN", "MISSING", List.of());
    var ids = new ArrayList<String>();
    for (var source : result.evidence()) {
      String identity =
          input.caseId()
              + "|"
              + input.patientId()
              + "|"
              + input.revision()
              + "|"
              + input.snapshotVersion()
              + "|"
              + input.proposedTreatment()
              + "|"
              + kind
              + "|"
              + source.id()
              + "|"
              + source.version()
              + "|"
              + source.section()
              + "|"
              + source.text();
      String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
      ids.add(id);
      observations.add(
          new EvidenceObservation(
              id,
              source.id(),
              source.version(),
              input.caseId(),
              input.patientId(),
              input.revision(),
              input.snapshotVersion(),
              input.proposedTreatment(),
              kind,
              Instant.now(),
              observationFacts(result, input),
              source));
    }
    String outcome =
        switch (kind) {
          case "SAFETY" ->
              !"true".equals(result.details().get("supported"))
                  ? "UNSUPPORTED"
                  : result.findings().stream().anyMatch(f -> f.severity().equals("BLOCKING"))
                      ? "BLOCKING"
                      : result.findings().stream().anyMatch(f -> f.severity().equals("REVIEW"))
                          ? "REVIEW_REQUIRED"
                          : "NO_BLOCKING_FINDING";
          case "COVERAGE" ->
              "true".equals(result.details().get("covered")) ? "COVERED" : "NOT_COVERED";
          case "FOLLOWUP" ->
              result.details().getOrDefault("followup", "").isBlank() ? "MISSING" : "AVAILABLE";
          default ->
              result.evidence().isEmpty()
                  ? "MISSING"
                  : result.revision() == input.revision()
                      ? "EVIDENCE_AVAILABLE"
                      : "REUSED_GUIDANCE_EVIDENCE";
        };
    return new AttemptCheck(
        kind, result.modelUsage() == null ? "FAILED" : "COMPLETED", outcome, List.copyOf(ids));
  }

  private static Map<String, String> observationFacts(SpecialistResult result, ReviewInput input) {
    var facts = new LinkedHashMap<>(result.details());
    facts.put("sourceRevision", Long.toString(result.revision()));
    facts.put("reused", Boolean.toString(result.revision() != input.revision()));
    return Map.copyOf(facts);
  }

  private static String reason(
      SpecialistResult safety, SpecialistResult care, boolean current, Readiness readiness) {
    if (safety == null) return "The safety check has not completed for this candidate.";
    if (!"true".equals(safety.details().get("supported")))
      return "The treatment is unsupported by the demonstration safety rules.";
    var blocking = safety.findings().stream().filter(f -> f.severity().equals("BLOCKING")).toList();
    if (!blocking.isEmpty())
      return "A blocking safety rule finding requires a revised request: "
          + String.join("; ", blocking.stream().map(Finding::message).toList());
    if (care == null) return "Coverage and follow-up checks have not completed.";
    if (!"true".equals(care.details().get("covered")))
      return "The fictional plan does not cover this candidate; this is an operational constraint.";
    if (care.details().getOrDefault("followup", "").isBlank())
      return "No follow-up option was returned.";
    if (current && readiness == Readiness.READY_FOR_CLINICIAN_REVIEW)
      return "Required safety, guidance, coverage and follow-up checks completed for the selected"
          + " draft.";
    if (current && readiness == Readiness.INCOMPLETE)
      return "One or more required checks did not complete.";
    return "The draft still requires review of the recorded findings.";
  }
}
