package com.healthcare.demo.triage.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.healthcare.demo.shared.*;
import com.healthcare.demo.shared.Contracts.*;
import com.healthcare.demo.triage.casework.*;
import com.healthcare.demo.triage.casework.ReviewModels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FlowLifecycleTest {
  @Test
  void rejectedAdmissionCancelsItsDeadline() {
    var store = mock(ReviewStore.class);
    when(store.claim("run")).thenReturn(true);
    var jobs = mock(ExecutorService.class);
    when(jobs.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException());
    var scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    try {
      var flow = flow(store, jobs, scheduler);
      flow.start(run());
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(scheduler.getQueue()).hasSize(1));
      verify(store)
          .finish(
              eq("run"),
              argThat(r -> r.readiness() == Readiness.INCOMPLETE && r.attempts().size() == 1));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void finishedFailureCancelsItsDeadline() {
    var store = mock(ReviewStore.class);
    var active = new AtomicBoolean(true);
    when(store.claim("run")).thenReturn(true);
    when(store.active("run")).thenAnswer(i -> active.get());
    doAnswer(
            i -> {
              active.set(false);
              return null;
            })
        .when(store)
        .finish(eq("run"), any());
    var scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    var jobs = Executors.newSingleThreadExecutor();
    try {
      flow(store, jobs, scheduler).start(run());
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(active).isFalse();
                assertThat(scheduler.getQueue()).hasSize(1);
              });
    } finally {
      scheduler.shutdownNow();
      jobs.shutdownNow();
    }
  }

  ReviewFlow flow(ReviewStore store, ExecutorService jobs, ScheduledExecutorService scheduler) {
    var tools = mock(McpAccessFactory.class);
    when(tools.open(anyString(), any(), any()))
        .thenThrow(new IllegalStateException("Test required tool outage"));
    return new ReviewFlow(
        store,
        mock(CapabilityTokens.class),
        mock(A2aClient.class),
        mock(ModelAssessment.class),
        tools,
        jobs,
        jobs,
        scheduler,
        "http://localhost:8081",
        "http://localhost:8082",
        "http://localhost:8083",
        "http://localhost:8084");
  }

  Run run() {
    var input = new ReviewInput("case", "P101", 1, "fixture", "Amoxicillin", "", List.of());
    return new Run(
        "run",
        "case",
        "clinician",
        1,
        input,
        "QUEUED",
        "queued",
        "",
        null,
        Instant.now(),
        Instant.now(),
        1,
        false,
        false);
  }
}
