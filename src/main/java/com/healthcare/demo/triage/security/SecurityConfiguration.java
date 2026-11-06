package com.healthcare.demo.triage.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

/** Local demo accounts. A real deployment would integrate the hospital's identity provider. */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService demoUsers(
      @Value("${demo.login-password}") String password,
      org.springframework.security.crypto.password.PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
        User.withUsername("clinician")
            .password(encoder.encode(password))
            .roles("CLINICIAN")
            .build(),
        User.withUsername("coordinator")
            .password(encoder.encode(password))
            .roles("COORDINATOR")
            .build());
  }

  @Bean
  SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/health",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/session",
                        "/api/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(
            cache ->
                cache.requestCache(
                    new org.springframework.security.web.savedrequest.NullRequestCache()))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .formLogin(
            login ->
                login
                    .loginProcessingUrl("/api/login")
                    .successHandler(
                        (req, res, auth) -> {
                          res.setContentType("application/json");
                          res.getWriter().write("{\"authenticated\":true}");
                        })
                    .failureHandler(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"error\":\"Sign in failed\"}");
                        }))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (req, res, e) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter().write("{\"error\":\"Please sign in\"}");
                    }));
    return http.build();
  }
}
