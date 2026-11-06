package com.healthcare.demo.triage.workflow;

import static org.assertj.core.api.Assertions.*;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Isolated PostgreSQL, no generation model and no writes to the presenter's database. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DEMO_CONTAINER_TEST", matches = "true")
class PostgresLeaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  static JdbcTemplate jdbc;
  static DriverManagerDataSource ds;

  @BeforeAll
  static void database() {
    ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new ResourceDatabasePopulator(
            new ClassPathResource("db/demo/V1__demo_schema.sql"),
            new ClassPathResource("db/demo/V2__policy_vectors.sql"),
            new ClassPathResource("db/demo/V3__review_request_fingerprint.sql"),
            new ClassPathResource("db/demo/V4__execution_leases.sql"))
        .execute(ds);
    jdbc = new JdbcTemplate(ds);
    jdbc.update(
        "INSERT INTO"
            + " clinical.patient(patient_id,name,age,version,allergy_status,preference,scenario,cohort,source_id,provenance)"
            + " VALUES('P101','Test',40,1,'REVIEWED','','test','test','test','test')");
    jdbc.update("INSERT INTO workflow.patient_grant VALUES('clinician','P101')");
  }

  ReviewStore store() {
    return new ReviewStore(jdbc, new DataSourceTransactionManager(ds));
  }

  StartRequest request() {
    return new StartRequest("P101", "Test", "Test", null, null, UUID.randomUUID().toString());
  }

  Result result() {
    return new Result(Readiness.INCOMPLETE, "Test", List.of(), List.of(), List.of(), "Test", 1);
  }

  @Test
  void twoInstancesDoNotInterruptLiveClaimsAndExpiredOwnersCannotPublish() throws Exception {
    var first = store();
    var second = store();
    var run = first.create("clinician", request());
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return first.claim(run.id());
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return second.claim(run.id());
              });
      gate.countDown();
      boolean ownsA = a.get(5, TimeUnit.SECONDS), ownsB = b.get(5, TimeUnit.SECONDS);
      assertThat(ownsA ^ ownsB).isTrue();
      var owner = ownsA ? first : second;
      var other = ownsA ? second : first;
      assertThat(other.recoverExpired()).isZero();
      assertThat(owner.active(run.id())).isTrue();
      other.finish(run.id(), result());
      assertThat(owner.get("clinician", run.id()).state()).isEqualTo("RUNNING");
      jdbc.update(
          "UPDATE workflow.review_run SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE"
              + " id=?",
          run.id());
      assertThat(other.recoverExpired()).isEqualTo(1);
      owner.maintainLeases();
      owner.finish(run.id(), result());
      owner.progress(run.id(), result());
      owner.stage(run.id(), "late");
      owner.recordSnapshot(run.id(), "late");
      owner.event(run.id(), "TEST", "late", Map.of(), 0);
      assertThat(owner.get("clinician", run.id()).state()).isEqualTo("INTERRUPTED");
      assertThat(owner.get("clinician", run.id()).result()).isNull();
      assertThat(owner.events("clinician", run.id(), 0).stream().map(Event::message))
          .doesNotContain("late");
      assertThatThrownBy(() -> owner.selectCandidate("clinician", run.id(), "late"))
          .isInstanceOf(Conflict.class);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void crossInstanceCancellationAndGracefulReleaseStayTerminal() {
    var first = store();
    var second = store();
    var run = first.create("clinician", request());
    first.claim(run.id());
    second.cancel("clinician", run.id());
    first.finish(run.id(), result());
    assertThat(first.get("clinician", run.id()).state()).isEqualTo("CANCELED");
    var own = first.create("clinician", request());
    first.claim(own.id());
    var theirs = second.create("clinician", request());
    second.claim(theirs.id());
    first.releaseOwned();
    assertThat(first.get("clinician", own.id()).state()).isEqualTo("INTERRUPTED");
    assertThat(second.get("clinician", theirs.id()).state()).isEqualTo("RUNNING");
    second.releaseOwned();
  }

  @Test
  void abandonedQueuedAdmissionsExpireWithoutRevival() {
    var first = store();
    var second = store();
    var fresh = first.create("clinician", request());
    assertThat(second.recoverExpired()).isZero();
    var orphan = first.create("clinician", request());
    jdbc.update(
        "UPDATE workflow.review_run SET created_at=CURRENT_TIMESTAMP - INTERVAL '91' SECOND WHERE"
            + " id=?",
        orphan.id());
    assertThat(first.claim(orphan.id())).isFalse();
    assertThat(second.recoverExpired()).isEqualTo(1);
    assertThat(first.claim(orphan.id())).isFalse();
    assertThat(second.get("clinician", fresh.id()).state()).isEqualTo("QUEUED");
  }

  @Test
  void simultaneousSubmissionReturnsSameCommittedRun() throws Exception {
    var first = store();
    var second = store();
    var request = request();
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return first.create("clinician", request).id();
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return second.create("clinician", request).id();
              });
      gate.countDown();
      assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(b.get(5, TimeUnit.SECONDS));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM workflow.review_run WHERE idempotency_key=?",
                  Integer.class,
                  request.idempotencyKey()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void specialistReplicaStartCancellationAndExpiryFenceLateResults() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executions = new AtomicInteger();
    Specialist work =
        (input, token, log) -> {
          executions.incrementAndGet();
          entered.countDown();
          boolean done = false;
          while (!done) {
            try {
              done = release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
          }
          return new SpecialistResult(
              "test",
              input.caseId(),
              input.patientId(),
              input.revision(),
              input.snapshotVersion(),
              "late",
              List.of(),
              List.of(),
              List.of(),
              Map.of(),
              List.of(),
              null);
        };
    var input = new ReviewInput("test-case", "P101", 1, "test", "test", "EHR-v1", List.of());
    var permission = new CapabilityTokens.Claims("clinician", "test-case", "P101", 1, List.of());
    var firstWorkers = Executors.newSingleThreadExecutor();
    var maintenance = Executors.newSingleThreadScheduledExecutor();
    try (var first = new AgentTasks(jdbc, "test", work, firstWorkers, maintenance);
        var second = new AgentTasks(jdbc, "test", work)) {
      String id = first.start(input, "clinician", "test-token", "one");
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(second.start(input, "clinician", "test-token", "one")).isEqualTo(id);
      assertThat(second.recoverExpired()).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workflow.agent_task WHERE id=?", String.class, id))
          .isEqualTo("working");
      second.cancel(id, permission);
      release.countDown();
      firstWorkers.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertThat(executions.get()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workflow.agent_task WHERE id=?", String.class, id))
          .isEqualTo("canceled");
      assertThat(
              jdbc.queryForObject(
                  "SELECT result_json FROM workflow.agent_task WHERE id=?", String.class, id))
          .isNull();
    } finally {
      release.countDown();
      firstWorkers.shutdownNow();
      maintenance.shutdownNow();
    }
  }

  @Test
  void specialistExpiredOwnerCannotRenewOrPublishAfterRecovery() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = Executors.newSingleThreadExecutor();
    var maintenance = Executors.newSingleThreadScheduledExecutor();
    Specialist work =
        (input, token, log) -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
          return new SpecialistResult(
              "expiry",
              input.caseId(),
              input.patientId(),
              input.revision(),
              input.snapshotVersion(),
              "late",
              List.of(),
              List.of(),
              List.of(),
              Map.of(),
              List.of(),
              null);
        };
    var input = new ReviewInput("expiry-case", "P101", 1, "test", "test", "EHR-v1", List.of());
    try (var first = new AgentTasks(jdbc, "expiry", work, workers, maintenance);
        var second = new AgentTasks(jdbc, "expiry", work)) {
      String id = first.start(input, "clinician", "test-token", "expired");
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      jdbc.update(
          "UPDATE workflow.agent_task SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE"
              + " id=?",
          id);
      assertThat(second.recoverExpired()).isEqualTo(1);
      first.maintainLeases();
      release.countDown();
      workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workflow.agent_task WHERE id=?", String.class, id))
          .isEqualTo("failed");
      assertThat(
              jdbc.queryForObject(
                  "SELECT result_json FROM workflow.agent_task WHERE id=?", String.class, id))
          .isNull();
    } finally {
      release.countDown();
      workers.shutdownNow();
      maintenance.shutdownNow();
    }
  }
}
