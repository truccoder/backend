package com.socialapp.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import reactor.netty.http.client.HttpClient;

/**
 * Gives every outbound {@code WebClient} a connect and a response timeout.
 *
 * <p>Until this existed, all eight of them were built as {@code WebClient.builder()...build()} with
 * no timeout anywhere: Gemini, MoMo, Stream Chat, Cloud Vision, OneSignal, and the three trending
 * crawlers. Those calls are made from request threads with {@code .block()}, so an upstream that
 * accepts the connection and then goes quiet holds a Tomcat thread indefinitely. Enough of them and
 * the pool is gone — at which point this service is down because somebody else's is.
 *
 * <p><b>Why a {@link WebClientCustomizer} rather than fixing the eight call sites by hand.</b> A
 * customizer is applied by Spring Boot to the auto-configured {@code WebClient.Builder}, so it also
 * covers the ninth client somebody adds next month. Fixing the call sites individually protects
 * only the code that exists today, and the failure it prevents is invisible until an upstream
 * misbehaves in production. The call sites do have to inject the managed {@code WebClient.Builder}
 * instead of calling the static {@code WebClient.builder()} factory — the static one is not a bean
 * and customizers never reach it.
 */
@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
@RequiredArgsConstructor
public class WebClientTimeoutConfig {

  private final HttpClientProperties properties;

  @Bean
  public WebClientCustomizer timeoutCustomizer() {
    return builder ->
        builder.clientConnector(
            connector(properties.getConnectTimeout(), properties.getResponseTimeout()));
  }

  /**
   * Builds a connector with explicit timeouts, for the one caller that needs different ones.
   *
   * <p>Public and static so {@code GeminiConfig} can override just the response timeout while
   * keeping the same connect timeout, rather than dropping the customizer's connector entirely and
   * silently losing both.
   */
  public static ReactorClientHttpConnector connector(
      Duration connectTimeout, Duration responseTimeout) {
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
            .responseTimeout(responseTimeout);
    return new ReactorClientHttpConnector(httpClient);
  }
}
