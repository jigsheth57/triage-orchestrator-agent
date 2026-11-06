package com.healthcare.demo.triage.workflow;

import com.healthcare.demo.shared.Contracts.*;

/**
 * Readiness is decided by required evidence and rule findings, never by wording in a model summary.
 */
public final class ReviewDecision {
  private ReviewDecision() {}

  public static Readiness decide(
      SpecialistResult safety, SpecialistResult guidance, SpecialistResult care) {
    if (safety == null || !"true".equals(safety.details().get("supported")))
      return Readiness.INCOMPLETE;
    if (safety.findings().stream().anyMatch(f -> f.severity().equals("BLOCKING")))
      return Readiness.BLOCKED;
    if (safety.modelUsage() == null
        || guidance == null
        || guidance.modelUsage() == null
        || guidance.evidence().isEmpty()
        || care == null
        || care.modelUsage() == null) return Readiness.INCOMPLETE;
    if (!sameContext(safety, guidance)
        || !sameContext(safety, care)
        || care.revision() != safety.revision()
        || guidance.revision() > safety.revision()) return Readiness.INCOMPLETE;
    if (!"true".equals(care.details().get("covered"))
        || care.details().getOrDefault("followup", "").isBlank()) return Readiness.REVIEW_REQUIRED;
    if (safety.findings().stream().anyMatch(f -> f.severity().equals("REVIEW")))
      return Readiness.REVIEW_REQUIRED;
    return Readiness.READY_FOR_CLINICIAN_REVIEW;
  }

  private static boolean sameContext(SpecialistResult first, SpecialistResult second) {
    return java.util.Objects.equals(first.caseId(), second.caseId())
        && java.util.Objects.equals(first.patientId(), second.patientId())
        && java.util.Objects.equals(first.snapshotVersion(), second.snapshotVersion());
  }
}
