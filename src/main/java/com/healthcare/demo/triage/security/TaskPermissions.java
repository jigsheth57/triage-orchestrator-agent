package com.healthcare.demo.triage.security;

import com.healthcare.demo.shared.CapabilityTokens;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class TaskPermissions {
  @Bean
  CapabilityTokens capabilities(@Value("${demo.token-secret}") String secret) {
    return new CapabilityTokens(secret, Clock.systemUTC());
  }
}
