package com.healthcare.demo.triage.casework;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persist cases and review events with ordinary SQL. Transactions protect version checks. */
@Repository
public class ReviewStore {
  private final JdbcTemplate jdbc;
  private final String executionOwner = UUID.randomUUID().toString();
  private static final String LIVE = " AND execution_owner=? AND lease_until>CURRENT_TIMESTAMP";
  private final TransactionTemplate transactions;

  public ReviewStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transactions = new TransactionTemplate(manager);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public void requireGrant(String actor, String patientId) {
    if (jdbc.queryForObject(
            "SELECT count(*) FROM workflow.patient_grant WHERE actor=? AND patient_id=?",
            Integer.class,
            actor,
            patientId)
        == 0) throw new SecurityException("This patient is not assigned to the current user");
  }

  public Run create(String actor, StartRequest request) {
    try {
      return createTransaction(actor, request);
    } catch (org.springframework.dao.DuplicateKeyException collision) {
      // The losing transaction has rolled back before the committed winner is read.
      var rows =
          jdbc.queryForList(
              "SELECT id,request_hash FROM workflow.review_run WHERE actor=? AND idempotency_key=?",
              actor,
              request.idempotencyKey());
      if (rows.isEmpty()) throw collision;
      if (!fingerprint(request).equals(rows.get(0).get("request_hash")))
        throw new Conflict("Submission key was already used for different inputs");
      return get(actor, rows.get(0).get("id").toString());
    }
  }

  private Run createTransaction(String actor, StartRequest request) {
    return transactions.execute(
        tx -> {
          requireGrant(actor, request.patientId());
          String hash = fingerprint(request);
          var existing =
              jdbc.queryForList(
                  "SELECT id,request_hash FROM workflow.review_run WHERE actor=? AND"
                      + " idempotency_key=?",
                  actor,
                  request.idempotencyKey());
          if (!existing.isEmpty()) {
            if (!hash.equals(existing.get(0).get("request_hash")))
              throw new Conflict("Submission key was already used for different inputs");
            return get(actor, existing.get(0).get("id").toString());
          }
          String caseId = request.caseId();
          long revision;
          List<String> reports = List.of();
          if (caseId == null || caseId.isBlank()) {
            caseId = UUID.randomUUID().toString();
            revision = 1;
            jdbc.update(
                "INSERT INTO"
                    + " workflow.case_header(id,patient_id,actor,version,preference,updated_at)"
                    + " VALUES(?,?,?,1,'',?)",
                caseId,
                request.patientId(),
                actor,
                now());
          } else {
            var c = caseForUpdate(actor, caseId);
            if (!request.patientId().equals(c.get("patient_id")))
              throw new Conflict("A case cannot change patients");
            long version = ((Number) c.get("version")).longValue();
            if (request.expectedVersion() == null || request.expectedVersion() != version)
              throw new Conflict("Case changed; refresh before submitting");
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM workflow.review_run WHERE case_id=? AND state IN"
                        + " ('QUEUED','RUNNING')",
                    Integer.class,
                    caseId)
                > 0) throw new Conflict("A review is already running for this case");
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM workflow.memory WHERE case_id=? AND confirmed=false AND"
                        + " expires_at>?",
                    Integer.class,
                    caseId,
                    now())
                > 0)
              throw new Conflict(
                  "Confirm the patient-reported change before starting a fresh review");
            revision = version + 1;
            jdbc.update(
                "UPDATE workflow.case_header SET version=?,updated_at=? WHERE id=?",
                revision,
                now(),
                caseId);
            reports = List.of(Json.read(c.get("reported_medications").toString(), String[].class));
          }
          String id = UUID.randomUUID().toString();
          var input =
              new ReviewInput(
                  caseId,
                  request.patientId(),
                  revision,
                  request.diagnosis().trim(),
                  request.proposedTreatment().trim(),
                  "",
                  reports);
          jdbc.update(
              "INSERT INTO"
                  + " workflow.review_run(id,case_id,actor,revision,input_json,state,stage,idempotency_key,created_at,updated_at,request_hash)"
                  + " VALUES(?,?,?,?,?,'QUEUED','Review queued',?,?,?,?)",
              id,
              caseId,
              actor,
              revision,
              Json.write(input),
              request.idempotencyKey(),
              now(),
              now(),
              hash);
          append(id, "WORKFLOW", "Review accepted", Map.of("revision", revision), 0);
          audit(actor, "REVIEW_STARTED", id);
          return get(actor, id);
        });
  }

  public Run get(String actor, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT r.*,c.version AS case_version FROM workflow.review_run r JOIN"
                + " workflow.case_header c ON c.id=r.case_id WHERE r.id=? AND r.actor=?",
            id,
            actor);
    if (rows.isEmpty()) throw new SecurityException("Review is not available to this user");
    var r = rows.get(0);
    var original = Json.read(r.get("input_json").toString(), ReviewInput.class);
    requireGrant(actor, original.patientId());
    long revision = ((Number) r.get("revision")).longValue(),
        version = ((Number) r.get("case_version")).longValue();
    String snapshot = r.get("snapshot_version") == null ? "" : r.get("snapshot_version").toString();
    boolean stale =
        revision != version
            || (!snapshot.isBlank() && !snapshot.equals(chartVersion(original.patientId())));
    boolean approved =
        !stale
            && jdbc.queryForObject(
                    "SELECT count(*) FROM workflow.approval WHERE run_id=? AND case_version=?",
                    Integer.class,
                    id,
                    version)
                > 0;
    return new Run(
        id,
        r.get("case_id").toString(),
        actor,
        revision,
        original,
        r.get("state").toString(),
        r.get("stage").toString(),
        snapshot,
        r.get("result_json") == null
            ? null
            : Json.read(r.get("result_json").toString(), Result.class),
        instant(r.get("created_at")),
        instant(r.get("updated_at")),
        version,
        approved,
        stale);
  }

  private Instant instant(Object value) {
    return value instanceof OffsetDateTime d
        ? d.toInstant()
        : value instanceof java.sql.Timestamp t ? t.toInstant() : Instant.parse(value.toString());
  }

  public ReviewInput currentInput(String actor, String id, String treatment) {
    var run = get(actor, id);
    return new ReviewInput(
        run.caseId(),
        run.input().patientId(),
        run.revision(),
        run.input().diagnosis(),
        treatment,
        run.snapshotVersion(),
        run.input().reportedMedications());
  }

  /** Only one submitter may start this persisted job, including after an HTTP retry. */
  public boolean claim(String id) {
    return jdbc.update(
            "UPDATE workflow.review_run SET"
                + " state='RUNNING',updated_at=?,execution_owner=?,lease_until=CURRENT_TIMESTAMP +"
                + " INTERVAL '120' SECOND WHERE id=? AND state='QUEUED' AND"
                + " created_at>CURRENT_TIMESTAMP - INTERVAL '90' SECOND",
            now(),
            executionOwner,
            id)
        == 1;
  }

  public boolean active(String id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM workflow.review_run WHERE id=? AND state='RUNNING'" + LIVE,
            Integer.class,
            id,
            executionOwner)
        > 0;
  }

  public void stage(String id, String stage) {
    jdbc.update(
        "UPDATE workflow.review_run SET state='RUNNING',stage=?,updated_at=? WHERE id=? AND state"
            + " IN ('QUEUED','RUNNING')"
            + LIVE,
        stage,
        now(),
        id,
        executionOwner);
  }

  public void recordSnapshot(String id, String snapshot) {
    jdbc.update(
        "UPDATE workflow.review_run SET snapshot_version=?,updated_at=? WHERE id=? AND state IN"
            + " ('QUEUED','RUNNING')"
            + LIVE,
        snapshot,
        now(),
        id,
        executionOwner);
  }

  public void rememberPreference(Run run, String preference) {
    transactions.executeWithoutResult(
        tx -> {
          caseForUpdate(run.actor(), run.caseId());
          jdbc.queryForList("SELECT id FROM workflow.review_run WHERE id=? FOR UPDATE", run.id());
          if (!active(run.id())) throw new Conflict("Execution owner is no longer active");
          jdbc.update(
              "UPDATE workflow.case_header SET preference=? WHERE id=? AND preference=''",
              preference,
              run.caseId());
          memory(
              run.caseId(), "preference", preference, true, "Confirmed synthetic chart preference");
        });
  }

  public ReviewInput selectCandidate(String actor, String id, String candidate) {
    return transactions.execute(
        tx -> {
          var run = get(actor, id);
          var c = caseForUpdate(actor, run.caseId());
          jdbc.queryForList("SELECT id FROM workflow.review_run WHERE id=? FOR UPDATE", id);
          if (!active(id) || ((Number) c.get("version")).longValue() != run.revision())
            throw new Conflict("Case changed during the review");
          long next = run.revision() + 1;
          jdbc.update(
              "UPDATE workflow.case_header SET version=?,updated_at=? WHERE id=?",
              next,
              now(),
              run.caseId());
          jdbc.update(
              "UPDATE workflow.review_run SET revision=?,updated_at=? WHERE id=?", next, now(), id);
          return new ReviewInput(
              run.caseId(),
              run.input().patientId(),
              next,
              run.input().diagnosis(),
              candidate,
              run.snapshotVersion(),
              run.input().reportedMedications());
        });
  }

  public void progress(String id, Result result) {
    jdbc.update(
        "UPDATE workflow.review_run SET result_json=?,updated_at=? WHERE id=? AND state IN"
            + " ('QUEUED','RUNNING')"
            + LIVE,
        Json.write(result),
        now(),
        id,
        executionOwner);
  }

  public void finish(String id, Result result) {
    transactions.executeWithoutResult(
        tx -> {
          int changed =
              jdbc.update(
                  "UPDATE workflow.review_run SET"
                      + " result_json=?,state='COMPLETED',stage=?,updated_at=? WHERE id=? AND state"
                      + " IN ('QUEUED','RUNNING')"
                      + LIVE,
                  Json.write(result),
                  result.readiness().name(),
                  now(),
                  id,
                  executionOwner);
          if (changed > 0)
            append(
                id,
                "RESULT",
                "Review finished",
                Map.of("readiness", result.readiness().name()),
                result.durationMs());
        });
  }

  public void event(
      String id, String kind, String message, Map<String, Object> details, long durationMs) {
    transactions.executeWithoutResult(
        tx -> {
          jdbc.queryForList("SELECT id FROM workflow.review_run WHERE id=? FOR UPDATE", id);
          if (active(id)) append(id, kind, message, details, durationMs);
        });
  }

  private void append(
      String id, String kind, String message, Map<String, Object> details, long durationMs) {
    jdbc.update(
        "INSERT INTO workflow.event(run_id,kind,message,details_json,occurred_at,duration_ms)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        kind,
        message,
        Json.write(details),
        now(),
        durationMs);
  }

  @SuppressWarnings("unchecked")
  public List<Event> events(String actor, String id, long after) {
    get(actor, id);
    return jdbc.query(
        "SELECT * FROM workflow.event WHERE run_id=? AND id>? ORDER BY id",
        (rs, n) ->
            new Event(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("message"),
                Json.read(rs.getString("details_json"), Map.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getLong("duration_ms")),
        id,
        after);
  }

  public void cancel(String actor, String id) {
    transactions.executeWithoutResult(
        tx -> {
          get(actor, id);
          int changed =
              jdbc.update(
                  "UPDATE workflow.review_run SET state='CANCELED',stage='Review"
                      + " canceled',updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')",
                  now(),
                  id);
          if (changed > 0) {
            append(id, "WORKFLOW", "Review canceled", Map.of(), 0);
            audit(actor, "REVIEW_CANCELED", id);
          }
        });
  }

  public void approve(String actor, String id, ApprovalRequest request) {
    transactions.executeWithoutResult(
        tx -> {
          var beforeLock = get(actor, id);
          caseForUpdate(actor, beforeLock.caseId());
          jdbc.queryForObject(
              "SELECT version FROM clinical.patient WHERE patient_id=? FOR UPDATE",
              Long.class,
              beforeLock.input().patientId());
          var run = get(actor, id);
          // The shared database lets this final metadata check and approval be atomic.
          // Clinical record contents still come through MCP; this reads only the version.
          if (run.stale()
              || run.caseVersion() != request.caseVersion()
              || run.revision() != request.proposalRevision()
              || !run.snapshotVersion().equals(request.snapshotVersion())
              || !chartVersion(run.input().patientId()).equals(request.snapshotVersion()))
            throw new Conflict("Review is stale; run the checks again");
          if (!request.attested()
              || !run.state().equals("COMPLETED")
              || run.result() == null
              || run.result().readiness() != Readiness.READY_FOR_CLINICIAN_REVIEW)
            throw new Conflict(
                "Only a current, complete draft can be approved after clinician review");
          int added =
              jdbc.update(
                  "INSERT INTO"
                      + " workflow.approval(run_id,actor,case_version,snapshot_version,approved_at)"
                      + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                  id,
                  actor,
                  request.caseVersion(),
                  request.snapshotVersion(),
                  now());
          if (added > 0) {
            audit(actor, "DRAFT_APPROVED", id);
            memory(
                run.caseId(),
                "decision",
                run.result().summary(),
                true,
                "Clinician-approved draft " + id);
          }
        });
  }

  public void addReport(String actor, String caseId, ChangeRequest request) {
    transactions.executeWithoutResult(
        tx -> {
          var c = caseForUpdate(actor, caseId);
          long version = ((Number) c.get("version")).longValue();
          if (version != request.expectedVersion())
            throw new Conflict("Case changed; refresh before adding information");
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM workflow.review_run WHERE case_id=? AND state IN"
                      + " ('QUEUED','RUNNING')",
                  Integer.class,
                  caseId)
              > 0) throw new Conflict("Cancel the active review before changing the case");
          var reports =
              new ArrayList<>(
                  List.of(Json.read(c.get("reported_medications").toString(), String[].class)));
          if (request.confirmed() && !reports.contains(request.medication().trim()))
            reports.add(request.medication().trim());
          jdbc.update(
              "UPDATE workflow.case_header SET version=?,reported_medications=?,updated_at=? WHERE"
                  + " id=?",
              version + 1,
              Json.write(reports),
              now(),
              caseId);
          memory(
              caseId,
              "medication-report",
              request.medication().trim(),
              request.confirmed(),
              "Patient report reviewed by " + actor);
          audit(actor, "CASE_INFORMATION_CHANGED", caseId);
        });
  }

  private void memory(String caseId, String kind, String value, boolean confirmed, String source) {
    String id =
        UUID.nameUUIDFromBytes(
                (caseId + kind + value).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    int changed =
        jdbc.update(
            "UPDATE workflow.memory SET confirmed=?,source=?,expires_at=? WHERE id=?",
            confirmed,
            source,
            now().plusDays(7),
            id);
    if (changed == 0)
      jdbc.update(
          "INSERT INTO workflow.memory(id,case_id,kind,value,confirmed,source,expires_at)"
              + " VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
          id,
          caseId,
          kind,
          value,
          confirmed,
          source,
          now().plusDays(7));
  }

  public Map<String, Object> caseHistory(String actor, String caseId) {
    var c = caseForRead(actor, caseId);
    var memory =
        jdbc.queryForList(
            "SELECT kind,value,confirmed,source,expires_at FROM workflow.memory WHERE case_id=? AND"
                + " expires_at>? ORDER BY kind",
            caseId,
            now());
    return Map.of(
        "caseId",
        caseId,
        "version",
        c.get("version"),
        "preference",
        c.get("preference"),
        "memory",
        memory,
        "runs",
        jdbc.queryForList(
            "SELECT id,state,revision,stage,created_at FROM workflow.review_run WHERE case_id=?"
                + " ORDER BY created_at DESC",
            caseId));
  }

  public List<Map<String, Object>> recent(String actor) {
    return jdbc.queryForList(
        "SELECT id,case_id,stage,state,created_at FROM workflow.review_run WHERE actor=? ORDER BY"
            + " created_at DESC LIMIT 10",
        actor);
  }

  /** Renew only this process's live claims; an expired owner cannot renew itself. */
  public void maintainLeases() {
    jdbc.update(
        "UPDATE workflow.review_run SET lease_until=CURRENT_TIMESTAMP + INTERVAL '120' SECOND WHERE"
            + " state='RUNNING'"
            + LIVE,
        executionOwner);
    recoverExpired();
  }

  public int recoverExpired() {
    return jdbc.update(
        "UPDATE workflow.review_run SET state='INTERRUPTED',stage='Execution admission or lease"
            + " expired; start a fresh review',updated_at=CURRENT_TIMESTAMP WHERE (state='RUNNING'"
            + " AND lease_until<=CURRENT_TIMESTAMP) OR (state='QUEUED' AND"
            + " created_at<=CURRENT_TIMESTAMP - INTERVAL '90' SECOND)");
  }

  /** Compatibility alias; unlike the former implementation this never interrupts a live owner. */
  public void interruptUnfinished() {
    recoverExpired();
  }

  public void releaseOwned() {
    jdbc.update(
        "UPDATE workflow.review_run SET state='INTERRUPTED',stage='Execution owner stopped; start a"
            + " fresh review',updated_at=CURRENT_TIMESTAMP WHERE state='RUNNING' AND"
            + " execution_owner=?",
        executionOwner);
  }

  private Map<String, Object> caseForRead(String actor, String id) {
    var rows =
        jdbc.queryForList("SELECT * FROM workflow.case_header WHERE id=? AND actor=?", id, actor);
    if (rows.isEmpty()) throw new SecurityException("Case is not available to this user");
    requireGrant(actor, rows.get(0).get("patient_id").toString());
    return rows.get(0);
  }

  private Map<String, Object> caseForUpdate(String actor, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM workflow.case_header WHERE id=? AND actor=? FOR UPDATE", id, actor);
    if (rows.isEmpty()) throw new SecurityException("Case is not available to this user");
    requireGrant(actor, rows.get(0).get("patient_id").toString());
    return rows.get(0);
  }

  private String chartVersion(String patientId) {
    return "EHR-v"
        + jdbc.queryForObject(
            "SELECT version FROM clinical.patient WHERE patient_id=?", Long.class, patientId);
  }

  private void audit(String actor, String action, String resource) {
    jdbc.update(
        "INSERT INTO audit.activity(actor,action,resource_id,occurred_at) VALUES(?,?,?,?)",
        actor,
        action,
        resource,
        now());
  }

  private String fingerprint(StartRequest request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(Json.write(request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
