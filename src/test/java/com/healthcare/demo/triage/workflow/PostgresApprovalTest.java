package com.healthcare.demo.triage.workflow;

import static org.assertj.core.api.Assertions.*;

import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** A case can change while an approval waits for its lock. Re-read after acquiring it. */
@EnabledIfEnvironmentVariable(named = "DEMO_POSTGRES_TEST", matches = "true")
class PostgresApprovalTest {
  @Test
  void approvalRejectsACaseChangedWhileWaitingForItsLock() throws Exception {
    var ds =
        new DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:55432/clinical_triage_demo",
            System.getenv("DEMO_DB_USER"),
            System.getenv("DEMO_DB_PASSWORD"));
    var jdbc = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    var store = new ReviewStore(jdbc, new DataSourceTransactionManager(ds));
    var run =
        store.create(
            "clinician",
            new StartRequest(
                "P101", "Test", "Amoxicillin", null, null, UUID.randomUUID().toString()));
    store.claim(run.id());
    store.recordSnapshot(run.id(), "EHR-v7");
    // Read the fixture version rather than assuming the seed was never edited.
    String version =
        "EHR-v"
            + jdbc.queryForObject(
                "SELECT version FROM clinical.patient WHERE patient_id='P101'", Long.class);
    store.recordSnapshot(run.id(), version);
    store.finish(
        run.id(),
        new Result(
            Readiness.READY_FOR_CLINICIAN_REVIEW,
            "Amoxicillin",
            List.of(),
            List.of(),
            List.of(),
            "Test only",
            1));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var writer =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT version FROM workflow.case_header WHERE id=? FOR UPDATE",
                            Long.class,
                            run.caseId());
                        jdbc.update(
                            "UPDATE workflow.case_header SET version=version+1 WHERE id=?",
                            run.caseId());
                        locked.countDown();
                        try {
                          release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(e);
                        }
                      }));
      assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
      var approval =
          pool.submit(
              () -> {
                store.approve("clinician", run.id(), new ApprovalRequest(1, 1, version, true));
                return true;
              });
      Thread.sleep(150);
      release.countDown();
      writer.get(3, TimeUnit.SECONDS);
      assertThatThrownBy(() -> approval.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(Conflict.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM workflow.approval WHERE run_id=?", Integer.class, run.id()))
          .isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
      jdbc.update("DELETE FROM workflow.event WHERE run_id=?", run.id());
      jdbc.update("DELETE FROM workflow.memory WHERE case_id=?", run.caseId());
      jdbc.update("DELETE FROM workflow.approval WHERE run_id=?", run.id());
      jdbc.update("DELETE FROM workflow.review_run WHERE id=?", run.id());
      jdbc.update("DELETE FROM workflow.case_header WHERE id=?", run.caseId());
    }
  }
}
