package com.healthcare.demo.triage.casework;

import com.healthcare.demo.shared.Contracts.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** The browser receives typed review state, never a guessed verdict from narrative text. */
public final class ReviewModels {
  private ReviewModels() {}

  public record StartRequest(
      @NotBlank @Size(max = 80) String patientId,
      @NotBlank @Size(max = 500) String diagnosis,
      @NotBlank @Size(max = 500) String proposedTreatment,
      String caseId,
      Long expectedVersion,
      @NotBlank @Size(max = 120) String idempotencyKey) {}

  public record ChangeRequest(
      long expectedVersion, @NotBlank @Size(max = 200) String medication, boolean confirmed) {}

  public record ApprovalRequest(
      long caseVersion,
      long proposalRevision,
      @NotBlank String snapshotVersion,
      boolean attested) {}

  public record Result(
      Readiness readiness,
      String selectedTreatment,
      List<SpecialistResult> reviews,
      List<Finding> findings,
      List<Evidence> evidence,
      String summary,
      long durationMs) {}

  public record Run(
      String id,
      String caseId,
      String actor,
      long revision,
      ReviewInput input,
      String state,
      String stage,
      String snapshotVersion,
      Result result,
      Instant createdAt,
      Instant updatedAt,
      long caseVersion,
      boolean approved,
      boolean stale) {}

  public record Event(
      long id,
      String kind,
      String message,
      Map<String, Object> details,
      Instant occurredAt,
      long durationMs) {}

  public static final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }
}
