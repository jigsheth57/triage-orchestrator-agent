package com.healthcare.demo.triage.workflow;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("demo")
public record ReviewEndpoints(
    @NotNull URI mcpUrl,
    @NotNull URI pharmacistUrl,
    @NotNull URI guidanceUrl,
    @NotNull URI careUrl) {
  public ReviewEndpoints {
    for (URI uri : new URI[] {mcpUrl, pharmacistUrl, guidanceUrl, careUrl}) {
      if (uri != null
          && (!java.util.Set.of("http", "https").contains(uri.getScheme())
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || uri.getQuery() != null
              || uri.getFragment() != null))
        throw new IllegalArgumentException(
            "Service endpoints require HTTP(S) origins without credentials or query");
    }
  }
}
