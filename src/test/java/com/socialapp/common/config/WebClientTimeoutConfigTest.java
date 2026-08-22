package com.socialapp.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Proves the response timeout actually fires, against a server that behaves the way a hung upstream
 * behaves: it completes the TCP handshake and then says nothing, forever.
 *
 * <p>This is the case worth testing because it is the one that has no natural end. A refused
 * connection or a DNS failure returns on its own; a socket that is open and silent does not, and
 * before {@link WebClientTimeoutConfig} existed the eight {@code WebClient}s in this codebase would
 * all have waited on it indefinitely — from request threads, via {@code .block()}, until Tomcat's
 * pool was gone.
 *
 * <p>Timeouts here are milliseconds rather than the configured defaults so the test is fast; what
 * is being verified is that the mechanism engages at all, not the specific numbers.
 */
class WebClientTimeoutConfigTest {

  private ServerSocket silentServer;
  private ExecutorService acceptor;
  private final List<Socket> accepted = new ArrayList<>();

  @BeforeEach
  void startSilentServer() throws IOException {
    silentServer = new ServerSocket(0);
    acceptor = Executors.newSingleThreadExecutor();
    acceptor.submit(
        () -> {
          while (!silentServer.isClosed()) {
            try {
              // Accept and hold. Never read, never write, never close — the connection stays open
              // and completely idle, which is exactly what a wedged upstream looks like.
              accepted.add(silentServer.accept());
            } catch (IOException e) {
              return;
            }
          }
        });
  }

  @AfterEach
  void stopSilentServer() throws IOException {
    for (Socket socket : accepted) {
      socket.close();
    }
    silentServer.close();
    acceptor.shutdownNow();
  }

  @Test
  @DisplayName("shouldAbortAHungRequest_soAStalledUpstreamCannotHoldTheCallingThread")
  void shouldAbortAHungRequest() {
    WebClient client =
        WebClient.builder()
            .clientConnector(
                WebClientTimeoutConfig.connector(Duration.ofMillis(500), Duration.ofMillis(300)))
            .baseUrl("http://localhost:" + silentServer.getLocalPort())
            .build();

    long startedAt = System.currentTimeMillis();

    assertThatThrownBy(() -> client.get().retrieve().bodyToMono(String.class).block())
        .as("a request to a socket that never answers must end by itself")
        .isNotNull();

    long elapsed = System.currentTimeMillis() - startedAt;
    assertThat(elapsed)
        .as(
            "the call returned after %d ms; with the response timeout set to 300 ms it must not "
                + "run on. Without a timeout this line is never reached at all — the test hangs.",
            elapsed)
        .isLessThan(5_000L);
  }

  /**
   * The wiring, not the mechanism — and the half that actually silently fails.
   *
   * <p>A {@link org.springframework.boot.web.reactive.function.client.WebClientCustomizer} is only
   * applied to the {@code WebClient.Builder} Spring Boot auto-configures. Code that calls the
   * static {@code WebClient.builder()} factory gets a plain builder that no customizer ever
   * touches, which is how all eight clients here were built before this change. Nothing warns about
   * that: the application starts, the calls work, and the timeout simply is not there.
   *
   * <p>{@link ApplicationContextRunner} is used instead of {@code @SpringBootTest} so this needs no
   * database, no Redis and no containers — the question is only whether the customizer reaches the
   * managed builder.
   */
  @Test
  @DisplayName("shouldApplyTheCustomizerToTheManagedBuilder_soEveryInjectedWebClientInheritsIt")
  void shouldApplyTheCustomizerToTheManagedBuilder() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
        .withUserConfiguration(WebClientTimeoutConfig.class)
        .withPropertyValues(
            "http-client.connect-timeout=500ms", "http-client.response-timeout=300ms")
        .run(
            context -> {
              // GIVEN a WebClient built the way the application builds them: from the injected
              // builder, with no timeout configured at the call site
              WebClient client =
                  context
                      .getBean(WebClient.Builder.class)
                      .baseUrl("http://localhost:" + silentServer.getLocalPort())
                      .build();

              long startedAt = System.currentTimeMillis();

              // WHEN it calls an upstream that never answers
              assertThatThrownBy(() -> client.get().retrieve().bodyToMono(String.class).block())
                  .isNotNull();

              // THEN it still gives up, because the customizer supplied the connector
              assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000L);
            });
  }

  @Test
  @DisplayName("shouldGiveUpOnAnUnreachableHost_soConnectAttemptsDoNotStackUpEither")
  void shouldGiveUpOnAnUnreachableHost() {
    // 203.0.113.0/24 is TEST-NET-3 (RFC 5737): reserved for documentation and guaranteed not to be
    // routed, so the connection attempt hangs rather than being refused — the behaviour a
    // connect timeout exists for.
    WebClient client =
        WebClient.builder()
            .clientConnector(
                WebClientTimeoutConfig.connector(Duration.ofMillis(300), Duration.ofSeconds(5)))
            .baseUrl("http://203.0.113.1:81")
            .build();

    long startedAt = System.currentTimeMillis();

    assertThatThrownBy(() -> client.get().retrieve().bodyToMono(String.class).block()).isNotNull();

    assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000L);
  }
}
