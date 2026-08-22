package com.socialapp.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.socialapp.AbstractIntegrationTest;

/**
 * Pins the shape of the actuator surface, on both sides at once: the health probes must answer
 * without a token, and nothing else may answer at all.
 *
 * <p>Both halves are load-bearing and they pull in opposite directions, which is why they are
 * asserted together. Docker's {@code HEALTHCHECK} and compose's {@code condition: service_healthy}
 * run before any user exists and have no credential to present, so the probes have to be
 * {@code permitAll}. But actuator ships a large catalogue of endpoints behind the same prefix —
 * {@code /actuator/env} prints every environment variable including {@code JWT_SECRET} and the
 * datasource password, {@code /actuator/heapdump} hands over the whole heap — and opening the
 * prefix for the probes would open those too the moment somebody widens the exposure list.
 *
 * <p>The failure mode this guards against is quiet in both directions: an over-tight config makes
 * every deploy hang on a health gate that can never pass, and an over-loose one leaks credentials
 * with a 200 and no error anywhere.
 *
 * <p>Requests go through {@link TestRestTemplate} without an {@code Authorization} header on
 * purpose — that is exactly what the Docker healthcheck does.
 */
class ActuatorExposureTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Nested
  @DisplayName("Health probes")
  class HealthProbes {

    @Test
    @DisplayName(
        "shouldAnswerReadinessProbeWithoutAuthentication_soDockerHealthcheckCanGateDeploys")
    void shouldAnswerReadinessProbeWithoutAuthentication() {
      // GIVEN a caller with no token at all, like Docker's HEALTHCHECK
      // WHEN it asks the readiness probe
      ResponseEntity<String> response =
          restTemplate.getForEntity("/actuator/health/readiness", String.class);

      // THEN it is served, and reports the service able to take traffic
      assertThat(response.getStatusCode())
          .as(
              "readiness must be reachable unauthenticated — the Docker HEALTHCHECK and compose's "
                  + "condition: service_healthy have no credential to present, so a 401 here "
                  + "means every deploy waits on a gate that can never open")
          .isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("shouldAnswerLivenessProbeWithoutAuthentication_soAProcessRestartCanBeDecided")
    void shouldAnswerLivenessProbeWithoutAuthentication() {
      ResponseEntity<String> response =
          restTemplate.getForEntity("/actuator/health/liveness", String.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("shouldReportOnlyAggregateStatus_soTheProbeDoesNotMapOutTheInfrastructure")
    void shouldReportOnlyAggregateStatus() {
      // WHEN an anonymous caller reads the aggregate health endpoint
      ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

      // THEN it is a bare status, with no per-dependency breakdown
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody())
          .as(
              "management.endpoint.health.show-details must stay 'never' — the detailed body names "
                  + "every backing service and reports connection errors verbatim, which is a map "
                  + "of the infrastructure handed to an unauthenticated caller")
          .doesNotContain("components")
          .doesNotContain("details")
          .doesNotContain("database")
          .doesNotContain("redis");
    }
  }

  @Nested
  @DisplayName("Everything else under /actuator")
  class OtherEndpoints {

    /**
     * Each of these is a real disclosure if it ever answers: {@code env} and {@code configprops}
     * print the resolved configuration (JWT secret, datasource password, OAuth client secrets),
     * {@code heapdump} and {@code threaddump} hand over process memory, {@code mappings} enumerates
     * every route, and {@code loggers} is writable — it can turn on SQL logging in production.
     *
     * <p><b>The assertion is "not 2xx", not "404".</b> Two independent mechanisms keep these shut
     * and they answer with different codes: the exposure allow-list means the endpoint is never
     * registered (a 404 from the dispatcher), while {@code anyRequest().authenticated()} rejects
     * the anonymous caller first (a 401 from the filter chain). Which one wins is an ordering
     * detail — {@code /actuator/env} currently returns 401 because security answers before
     * dispatch. Pinning either specific code turns a harmless reordering into a red build, while
     * a body that comes back 200 is the thing that must never happen.
     */
    @Test
    @DisplayName("shouldNotServeAnyEndpointBesidesHealth_soConfigurationAndSecretsStayUnreadable")
    void shouldNotServeAnyEndpointBesidesHealth() {
      for (String endpoint :
          new String[] {
            "env",
            "configprops",
            "beans",
            "mappings",
            "loggers",
            "heapdump",
            "threaddump",
            "metrics",
            "info",
            "shutdown"
          }) {
        ResponseEntity<String> response =
            restTemplate.getForEntity("/actuator/" + endpoint, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
            .as(
                "/actuator/%s answered successfully — management.endpoints.web.exposure.include is "
                    + "an allow-list and 'health' is the only entry that belongs on it. Status was "
                    + "%s",
                endpoint, response.getStatusCode())
            .isFalse();
      }
    }
  }
}
