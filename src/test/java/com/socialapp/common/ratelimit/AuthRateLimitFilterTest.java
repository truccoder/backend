package com.socialapp.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;

/**
 * Component (unit) tests for {@link AuthRateLimitFilter}.
 *
 * <p>Redis is mocked rather than run, so these assert the filter's decisions — which paths it acts
 * on, what it does at the boundary, and what it keys the counter on — not Redis semantics. The
 * counting itself belongs to {@link FixedWindowRateLimiter}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthRateLimitFilterTest {

  private static final String AUTH_PATH = "/v1/api/auth/login";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private FilterChain filterChain;

  private AuthRateLimitFilter filter;
  private AuthRateLimitProperties properties;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    properties = new AuthRateLimitProperties();
    properties.setRequests(20);
    properties.setWindow(Duration.ofMinutes(5));
    filter = new AuthRateLimitFilter(new FixedWindowRateLimiter(redisTemplate), properties);
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setRemoteAddr("203.0.113.9");
    return request;
  }

  @Nested
  @DisplayName("shouldNotFilter")
  class ShouldNotFilter {

    @Test
    @DisplayName("ignores a path outside the auth tree")
    void ignoresNonAuthPath() {
      assertThat(filter.shouldNotFilter(request("/v1/api/posts/public"))).isTrue();
    }

    @Test
    @DisplayName("acts on every path under the auth tree")
    void actsOnAuthPaths() {
      for (String path :
          new String[] {
            "/v1/api/auth/login",
            "/v1/api/auth/forgot-password",
            "/v1/api/auth/magic-link",
            "/v1/api/auth/refresh",
            "/v1/api/auth/google/callback"
          }) {
        assertThat(filter.shouldNotFilter(request(path))).as(path).isFalse();
      }
    }

    @Test
    @DisplayName("applies to signed-in callers too, unlike the guest limiter")
    void appliesToAuthenticatedCallers() {
      // GIVEN a caller who already holds a valid token
      SecurityContextHolder.getContext()
          .setAuthentication(new UsernamePasswordAuthenticationToken("someone", null, List.of()));

      // THEN the auth tree is still limited for them — holding a token for one account is not a
      // reason to be allowed unlimited guesses at another account's password
      assertThat(filter.shouldNotFilter(request(AUTH_PATH))).isFalse();
    }
  }

  @Nested
  @DisplayName("doFilterInternal")
  class DoFilterInternal {

    @Test
    @DisplayName("lets the request through while inside the budget")
    void allowsWithinBudget() throws Exception {
      // GIVEN this is the 20th request in the window, exactly at the limit
      when(valueOperations.increment(anyString())).thenReturn(20L);
      MockHttpServletRequest request = request(AUTH_PATH);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilterInternal(request, response, filterChain);

      // THEN the boundary value is still allowed — "requests: 20" means 20 are permitted, and the
      // rejection starts at 21. Getting this off by one locks a user out one attempt early.
      verify(filterChain).doFilter(request, response);
      assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("rejects with 429 and Retry-After once over the budget")
    void rejectsOverBudget() throws Exception {
      // GIVEN one request past the limit
      when(valueOperations.increment(anyString())).thenReturn(21L);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilterInternal(request(AUTH_PATH), response, filterChain);

      // THEN the chain is never entered, so no password is ever checked
      verify(filterChain, never()).doFilter(any(), any());
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isEqualTo("300");
    }

    @Test
    @DisplayName("says nothing about which account, so it cannot be used to enumerate users")
    void responseRevealsNothingAboutTheAccount() throws Exception {
      when(valueOperations.increment(anyString())).thenReturn(21L);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilterInternal(request(AUTH_PATH), response, filterChain);

      // A throttle whose message differs between a real and an unknown account is an oracle for
      // discovering which emails are registered.
      assertThat(response.getContentAsString())
          .contains("Too many authentication attempts")
          .doesNotContain("account")
          .doesNotContain("email")
          .doesNotContain("user");
    }

    @Test
    @DisplayName("counts per source address, so one attacker cannot spend everyone's budget")
    void countsPerSourceAddress() throws Exception {
      when(valueOperations.increment(anyString())).thenReturn(1L);

      filter.doFilterInternal(request(AUTH_PATH), new MockHttpServletResponse(), filterChain);

      verify(valueOperations).increment("ratelimit:auth:203.0.113.9");
    }

    @Test
    @DisplayName("fails open when Redis is unavailable")
    void failsOpenWhenRedisIsDown() throws Exception {
      // GIVEN Redis is refusing connections
      when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilterInternal(request(AUTH_PATH), response, filterChain);

      // THEN sign-in still works. A limiter that cannot count is a degraded defence; one that
      // rejects everything locks every user out of the product.
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }
}
