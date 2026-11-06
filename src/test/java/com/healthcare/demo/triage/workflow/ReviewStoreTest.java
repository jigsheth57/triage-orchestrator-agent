package com.healthcare.demo.triage.workflow;

import static org.assertj.core.api.Assertions.*;

import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ReviewStoreTest {
  ReviewStore store;
  JdbcTemplate jdbc;

  @BeforeEach
  void database() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
            "sa",
            "");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/demo/V1__demo_schema.sql"),
            new ClassPathResource("db/demo/V3__review_request_fingerprint.sql"),
            new ClassPathResource("db/demo/V4__execution_leases.sql"))
        .execute(ds);
    jdbc = new JdbcTemplate(ds);
    jdbc.update(
        "INSERT INTO"
            + " clinical.patient(patient_id,name,age,version,allergy_status,preference,scenario,cohort,source_id,provenance)"
            + " VALUES('P101','Synthetic',45,7,'REVIEWED','Evening','allergy','hospital-demo-v1','fixture','Authored')");
    jdbc.update("INSERT INTO workflow.patient_grant VALUES('clinician','P101')");
    store = new ReviewStore(jdbc, new DataSourceTransactionManager(ds));
  }

  StartRequest request(String key) {
    return new StartRequest("P101", "Synthetic diagnosis", "Amoxicillin", null, null, key);
  }

  @Test
  void duplicateSubmissionsReturnTheSameRun() {
    var first = store.create("clinician", request("same"));
    assertThat(store.create("clinician", request("same")).id()).isEqualTo(first.id());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow.review_run", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                store.create(
                    "clinician",
                    new StartRequest("P101", "Changed diagnosis", "Other", null, null, "same")))
        .isInstanceOf(Conflict.class);
  }

  @Test
  void aQueuedRunCanOnlyBeClaimedOnce() {
    var run = store.create("clinician", request("claim"));
    assertThat(store.claim(run.id())).isTrue();
    assertThat(store.claim(run.id())).isFalse();
  }

  @Test
  void anUnassignedPatientCannotBeRead() {
    assertThatThrownBy(() -> store.requireGrant("stranger", "P101"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void aNewFactInvalidatesThePreviousApproval() {
    var run = store.create("clinician", request("approve"));
    store.claim(run.id());
    store.recordSnapshot(run.id(), "EHR-v7");
    store.finish(
        run.id(),
        new Result(
            Readiness.READY_FOR_CLINICIAN_REVIEW,
            "Amoxicillin",
            List.of(),
            List.of(),
            List.of(),
            "Synthetic result",
            10));
    var approval = new ApprovalRequest(1, 1, "EHR-v7", true);
    store.approve("clinician", run.id(), approval);
    assertThat(store.get("clinician", run.id()).approved()).isTrue();
    store.addReport("clinician", run.caseId(), new ChangeRequest(1, "Apixaban", true));
    assertThat(store.get("clinician", run.id()).stale()).isTrue();
    assertThat(store.get("clinician", run.id()).approved()).isFalse();
    assertThatThrownBy(() -> store.approve("clinician", run.id(), approval))
        .isInstanceOf(Conflict.class);
  }

  @Test
  void anIncompleteResultCannotBeApproved() {
    var run = store.create("clinician", request("incomplete"));
    store.claim(run.id());
    store.recordSnapshot(run.id(), "EHR-v7");
    store.finish(
        run.id(),
        new Result(
            Readiness.INCOMPLETE,
            "Amoxicillin",
            List.of(),
            List.of(),
            List.of(),
            "Missing evidence",
            10));
    assertThatThrownBy(
            () -> store.approve("clinician", run.id(), new ApprovalRequest(1, 1, "EHR-v7", true)))
        .isInstanceOf(Conflict.class);
  }
}
