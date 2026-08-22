package com.socialapp.common.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Caps how fast one IP address can hit {@code /v1/api/auth/**}.
 *
 * <p>That tree is {@code permitAll} because it has to be — nobody has a token before signing in —
 * and until this existed nothing else limited it either. {@link GuestRateLimitFilter} does not
 * cover it: its path list is the guest-readable <em>read</em> surface, and auth is not on it. So
 * the endpoints most worth attacking were the only ones with no ceiling at all:
 *
 * <ul>
 *   <li>{@code /login} — unlimited password guessing against a known email
 *   <li>{@code /forgot-password}, {@code /magic-link} — unlimited outbound mail, which burns the
 *       SMTP quota for everyone and turns the service into a way to bury someone's inbox
 *   <li>{@code /refresh} — unlimited attempts to find a live refresh token
 * </ul>
 *
 * <p>Applies to <b>every</b> caller here, not only anonymous ones, which is the opposite of {@link
 * GuestRateLimitFilter}. Holding a valid token is not a reason to be allowed unlimited attempts at
 * a different account's password, and every one of these endpoints is reachable without signing in
 * anyway, so "authenticated" is not a meaningful exemption on this path.
 *
 * <p>Runs <b>before</b> the JWT filter. There is nothing in the security context to consult and
 * nothing worth spending a signature verification on for a request that is about to be rejected.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

  private static final String KEY_PREFIX = "ratelimit:auth:";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final FixedWindowRateLimiter rateLimiter;
  private final AuthRateLimitProperties properties;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH_MATCHER.match(properties.getPathPattern(), request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String ip = clientIp(request);
    if (rateLimiter.isOverLimit(
        KEY_PREFIX + ip, properties.getRequests(), properties.getWindow())) {
      // Logged at WARN, unlike the guest limiter's silence: a caller burning through the auth
      // budget is a credential-stuffing signal worth seeing in Axiom, not routine traffic.
      log.warn("Auth rate limit exceeded for {} on {}", ip, request.getRequestURI());
      writeTooManyRequests(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

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
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(properties.getWindow().toSeconds()));
    // The message says nothing about which account or whether it exists — a throttle that
    // distinguishes "too many attempts on a real account" from "on an unknown one" is an account
    // enumeration oracle.
    response
        .getWriter()
        .write(
            """
            {"code":429,"error":"Too Many Requests",\
            "message":"Too many authentication attempts. Please try again later.",\
            "path":"%s"}"""
                .formatted(request.getRequestURI()));
  }
}
