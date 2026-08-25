package com.socialapp.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.socialapp.security.entity.UserEntity;

import jakarta.servlet.FilterChain;

/**
 * Component (unit) tests for {@link GuestRateLimitFilter}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.2.2 boundary value analysis on the request ceiling, Section 4.3.2
 * branch testing over the pass/throttle/fail-open paths).
 *
 * <p>Driven directly rather than through MockMvc: the filter is deliberately absent from the
 * {@code @WebMvcTest} slices (see {@code RateLimitConfig}), because those have no Redis.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuestRateLimitFilterTest {

  private static final String GUEST_PATH = "/v1/api/posts/public";
  private static final String CLIENT_IP = "203.0.113.7";

  @Mock private StringRedisTemplate redisTemplate;

  private GuestRateLimitProperties properties;
  private GuestRateLimitFilter filter;

  @BeforeEach
  void setUp() {
    properties = new GuestRateLimitProperties();
    properties.setRequests(3);
    properties.setWindow(Duration.ofMinutes(1));
    filter = new GuestRateLimitFilter(new FixedWindowRateLimiter(redisTemplate), properties);
  }

  /** Makes the next limiter call report {@code count} events so far in the window. */
  private void stubCount(long count) {
    when(redisTemplate.execute(
            ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.<Object>any()))
        .thenReturn(count);
  }

  private void assertCountedAgainst(String key) {
    verify(redisTemplate)
        .execute(
            ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of(key)),
            ArgumentMatchers.<Object>any());
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static MockHttpServletRequest guestRequest(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(CLIENT_IP);
    return request;
  }

  private static void signIn() {
    UserEntity user = new UserEntity();
    user.setId(1);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
  }

  @Nested
  @DisplayName("shouldNotFilter")
  class ShouldNotFilter {

    @Test
    @DisplayName("skips a path that is not open to guests")
    void skipsUnlistedPath() {
      // When / Then — the limit exists for the open read surface, not for the whole API
      assertThat(filter.shouldNotFilter(guestRequest("/v1/api/friendships"))).isTrue();
    }

    @Test
    @DisplayName("skips a signed-in caller even on a guest-readable path")
    void skipsAuthenticatedCaller() {
      // Given
      signIn();

      // When / Then — a signed-in user is identified and bannable; sharing an office IP should not
      // make them share a scraping budget with a stranger
      assertThat(filter.shouldNotFilter(guestRequest(GUEST_PATH))).isTrue();
    }

    @Test
    @DisplayName("applies to an anonymous caller on a guest-readable path")
    void appliesToGuest() {
      // When / Then
      assertThat(filter.shouldNotFilter(guestRequest(GUEST_PATH))).isFalse();
    }
  }

  @Nested
  @DisplayName("doFilterInternal")
  class DoFilterInternal {

    @Test
    @DisplayName("lets the request through and counts it, carrying the window with the call")
    void firstRequestIsCountedWithTheWindow() throws Exception {
      // Given
      stubCount(1L);
      MockHttpServletResponse response = new MockHttpServletResponse();
      FilterChain chain = new MockFilterChain();

      // When
      filter.doFilter(guestRequest(GUEST_PATH), response, chain);

      // Then — counting and the expiry now travel together in one Lua script, so the assertion is
      // that the window reached Redis, not that a second EXPIRE command was issued. See
      // FixedWindowRateLimiterTest for why they had to stop being two commands.
      assertThat(response.getStatus()).isEqualTo(200);
      verify(redisTemplate)
          .execute(
              ArgumentMatchers.<RedisScript<Long>>any(),
              eq(List.of("ratelimit:guest:" + CLIENT_IP)),
              eq(String.valueOf(Duration.ofMinutes(1).toMillis())));
    }

    @Test
    @DisplayName("allows the last request inside the limit and rejects the first one past it")
    void boundaryAtTheLimit() throws Exception {
      // Given: the ceiling is 3
      stubCount(3L);
      MockHttpServletResponse atLimit = new MockHttpServletResponse();
      filter.doFilter(guestRequest(GUEST_PATH), atLimit, new MockFilterChain());

      stubCount(4L);
      MockHttpServletResponse overLimit = new MockHttpServletResponse();
      filter.doFilter(guestRequest(GUEST_PATH), overLimit, new MockFilterChain());

      // Then
      assertThat(atLimit.getStatus()).isEqualTo(200);
      assertThat(overLimit.getStatus()).isEqualTo(429);
      assertThat(overLimit.getHeader("Retry-After")).isEqualTo("60");
      assertThat(overLimit.getContentAsString()).contains("Too Many Requests");
    }

    @Test
    @DisplayName("fails open when Redis is unreachable")
    void failsOpenWhenRedisIsDown() throws Exception {
      // Given
      when(redisTemplate.execute(
              ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.<Object>any()))
          .thenThrow(new IllegalStateException("Redis unreachable"));
      MockHttpServletResponse response = new MockHttpServletResponse();

      // When
      filter.doFilter(guestRequest(GUEST_PATH), response, new MockFilterChain());

      // Then — a limiter that cannot count is a degraded defence; one that rejects everything is
      // an outage on the first page an anonymous visitor sees
      assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ignores X-Forwarded-For unless it is explicitly trusted")
    void ignoresForwardedForByDefault() throws Exception {
      // Given: a caller trying to give itself a fresh identity per request
      stubCount(1L);
      MockHttpServletRequest request = guestRequest(GUEST_PATH);
      request.addHeader("X-Forwarded-For", "198.51.100.99");

      // When
      filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      // Then — counted against the socket address, so spoofing the header buys nothing
      assertCountedAgainst("ratelimit:guest:" + CLIENT_IP);
    }

    @Test
    @DisplayName("uses the left-most X-Forwarded-For entry when a proxy is trusted")
    void usesForwardedForWhenTrusted() throws Exception {
      // Given
      properties.setTrustForwardedFor(true);
      stubCount(1L);
      MockHttpServletRequest request = guestRequest(GUEST_PATH);
      request.addHeader("X-Forwarded-For", "198.51.100.99, 10.0.0.1");

      // When
      filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      // Then — left-most is the original client, the rest are proxies it passed through
      assertCountedAgainst("ratelimit:guest:198.51.100.99");
    }
  }
}
