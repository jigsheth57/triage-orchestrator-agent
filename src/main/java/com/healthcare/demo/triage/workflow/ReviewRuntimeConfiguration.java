package com.healthcare.demo.triage.workflow;

import com.healthcare.demo.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Import(ModelConfiguration.class)
@EnableConfigurationProperties({RuntimeProperties.class, ReviewEndpoints.class})
public class ReviewRuntimeConfiguration {
  @Bean
  A2aClient a2aClient(
      CapabilityTokens tokens, RestClient.Builder builder, RuntimeProperties properties) {
    return new A2aClient(tokens, builder, properties.connectTimeout(), properties.readTimeout());
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService reviewJobs(RuntimeProperties properties, MeterRegistry meters) {
    return ManagedExecutors.bounded(
        "reviews", properties.reviewWorkers(), properties.reviewQueue(), meters);
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService reviewConsultations(RuntimeProperties properties, MeterRegistry meters) {
    return ManagedExecutors.bounded(
        "consultations", properties.consultationWorkers(), properties.consultationQueue(), meters);
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService reviewStreams(RuntimeProperties properties, MeterRegistry meters) {
    return ManagedExecutors.bounded("streams", properties.streamWorkers(), 0, meters);
  }

  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService reviewDeadlines() {
    return ManagedExecutors.scheduled("review-deadlines");
  }
}
