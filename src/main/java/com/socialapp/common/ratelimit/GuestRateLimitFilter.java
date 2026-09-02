package com.socialapp.common.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.socialapp.security.util.SecurityUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Caps how fast one IP address can read the endpoints that are open to guests.
 *
 * <p>Opening the discovery feed and public profiles to unauthenticated readers turns them into a
 * scraping surface: {@code /posts/public} walks every public post in the system by cursor, and
 * {@code /users/{username}/profile} resolves handles to ids. Neither has a user id to hold
 * responsible, so the only handle left is the IP.
 *
 * <p><b>Fixed window, not a token bucket, and no new dependency.</b> A fixed window lets a caller
 * spend two windows' worth of requests across a boundary; that is a real weakness and an accepted
 * one — the goal here is to stop a bulk crawl, not to smooth traffic. It is {@code INCR} plus a
 * first-write {@code EXPIRE}, which is two Redis commands and no library, and it follows the
 * counter pattern {@code SpamDetector} already uses in this codebase.
 *
 * <p>Applies to <b>unauthenticated requests only</b>. A signed-in caller has an id, an account and
 * a ban mechanism behind them; sharing an office IP should not make them share a scraping budget
 * with a stranger.
 */
@Slf4j
@RequiredArgsConstructor
public class GuestRateLimitFilter extends OncePerRequestFilter {

  private static final String KEY_PREFIX = "ratelimit:guest:";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final FixedWindowRateLimiter rateLimiter;
  private final GuestRateLimitProperties properties;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Nothing here checks the "enabled" flag: when it is off this filter is never created at all
    // (see RateLimitConfig), so a disabled limiter costs nothing per request rather than costing a
    // branch. It also means a @WebMvcTest slice, which has no Redis to count in, does not need one.

    // Signed-in callers are identified and accountable; the limit is for anonymous traffic.
    if (SecurityUtils.getCurrentUserIdOrNull() != null) {
      return true;
    }
    return properties.getPaths().stream()
        .noneMatch(pattern -> PATH_MATCHER.match(pattern, request.getRequestURI()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (isOverLimit(clientIp(request))) {
      writeTooManyRequests(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean isOverLimit(String ip) {
    return rateLimiter.isOverLimit(
        KEY_PREFIX + ip, properties.getRequests(), properties.getWindow());
  }

  /**
   * The address the limit is counted against.
   *
   * <p>{@code X-Forwarded-For} is only read when explicitly enabled, because a client can set it
   * to anything. Trusting it without a proxy in front turns the limiter into an ornament: a
   * scraper simply sends a new fake IP with each request and never shares a counter with itself.
   */
  private String clientIp(HttpServletRequest request) {
    if (properties.isTrustForwardedFor()) {
      String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
      if (forwarded != null && !forwarded.isBlank()) {
        // Left-most entry is the original client; the rest are the proxies it passed through.
        return forwarded.split(",")[0].trim();
      }
    }
    return request.getRemoteAddr();
  }

  private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    // Retry-After in seconds, as RFC 9110 §10.2.3 requires — a client that is being throttled
    // needs to be told how long for, or it will simply retry immediately and stay throttled.
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(properties.getWindow().toSeconds()));
    response
        .getWriter()
        .write(
            RateLimitErrorBody.tooManyRequests(
                "Too many requests from this address. Sign in for a higher limit.",
                request.getRequestURI()));
  }
}
