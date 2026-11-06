package com.healthcare.demo.triage.workflow;

import static org.assertj.core.api.Assertions.*;

import com.healthcare.demo.shared.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewDecisionTest {
  SpecialistResult result(String agent, List<Finding> findings, Map<String, String> details) {
    return new SpecialistResult(
        agent,
        "case",
        "P101",
        1,
        "EHR-v7",
        "Actual model assessment",
        findings,
        List.of(new Evidence("source", "Source", "v1", "1", "Fixture", List.of())),
        List.of(),
        details,
        List.of(),
        new ModelUsage("local-model", 12, 20L, 10L));
  }

  @Test
  void missingRequiredWorkIsIncomplete() {
    assertThat(ReviewDecision.decide(null, null, null)).isEqualTo(Readiness.INCOMPLETE);
  }

  @Test
  void aHardFindingCannotBeOverruledByFluentText() {
    var safety =
        result(
            "pharmacist",
            List.of(new Finding("ALLERGY", "BLOCKING", "Concern", "A-17")),
            Map.of("supported", "true"));
    assertThat(
            ReviewDecision.decide(
                safety,
                result("guidance", List.of(), Map.of()),
                result("care", List.of(), Map.of("covered", "true"))))
        .isEqualTo(Readiness.BLOCKED);
  }

  @Test
  void coverageIsAnOperationalReviewIssue() {
    assertThat(
            ReviewDecision.decide(
                result("pharmacist", List.of(), Map.of("supported", "true")),
                result("guidance", List.of(), Map.of()),
                result("care", List.of(), Map.of("covered", "false"))))
        .isEqualTo(Readiness.REVIEW_REQUIRED);
  }

  @Test
  void missingFollowupRequiresHumanReview() {
    assertThat(
            ReviewDecision.decide(
                result("pharmacist", List.of(), Map.of("supported", "true")),
                result("guidance", List.of(), Map.of()),
                result("care", List.of(), Map.of("covered", "true", "followup", ""))))
        .isEqualTo(Readiness.REVIEW_REQUIRED);
  }

  @Test
  void completeFixtureChecksAreReadyForHumanReview() {
    assertThat(
            ReviewDecision.decide(
                result("pharmacist", List.of(), Map.of("supported", "true")),
                result("guidance", List.of(), Map.of()),
                result("care", List.of(), Map.of("covered", "true", "followup", "Evening option"))))
        .isEqualTo(Readiness.READY_FOR_CLINICIAN_REVIEW);
  }

  @Test
  void oldCoverageOrAnotherPatientCannotClearARevisedDraft() {
    var base = result("pharmacist", List.of(), Map.of("supported", "true"));
    var safety =
        new SpecialistResult(
            base.agent(),
            base.caseId(),
            base.patientId(),
            2,
            base.snapshotVersion(),
            base.summary(),
            base.findings(),
            base.evidence(),
            base.candidates(),
            base.details(),
            base.activities(),
            base.modelUsage());
    var oldCare = result("care", List.of(), Map.of("covered", "true", "followup", "visit"));
    var guidance = result("guidance", List.of(), Map.of());
    assertThat(ReviewDecision.decide(safety, guidance, oldCare)).isEqualTo(Readiness.INCOMPLETE);
    var otherPatient =
        new SpecialistResult(
            oldCare.agent(),
            oldCare.caseId(),
            "P104",
            2,
            oldCare.snapshotVersion(),
            oldCare.summary(),
            oldCare.findings(),
            oldCare.evidence(),
            oldCare.candidates(),
            oldCare.details(),
            oldCare.activities(),
            oldCare.modelUsage());
    assertThat(ReviewDecision.decide(safety, guidance, otherPatient))
        .isEqualTo(Readiness.INCOMPLETE);
    var currentCare =
        new SpecialistResult(
            oldCare.agent(),
            oldCare.caseId(),
            oldCare.patientId(),
            2,
            oldCare.snapshotVersion(),
            oldCare.summary(),
            oldCare.findings(),
            oldCare.evidence(),
            oldCare.candidates(),
            oldCare.details(),
            oldCare.activities(),
            oldCare.modelUsage());
    assertThat(ReviewDecision.decide(safety, guidance, currentCare))
        .isEqualTo(Readiness.READY_FOR_CLINICIAN_REVIEW);
  }
}
