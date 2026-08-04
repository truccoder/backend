package com.socialapp.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the guest rate limiter, and only when it is switched on.
 *
 * <p>Declared here as a conditional {@code @Bean} rather than annotated {@code @Component} on the
 * filter itself, for two reasons that both matter:
 *
 * <ul>
 *   <li>Turning the limiter off should cost <em>nothing</em> — no bean, no per-request branch —
 *       rather than constructing a filter that immediately opts out of every request.
 *   <li>The filter needs Redis. A {@code @WebMvcTest} slice has no Redis, and a {@code @Component}
 *       filter would be pulled into every one of those slices (Spring Boot's web slice includes
 *       {@code Filter} beans), forcing ~25 controller tests to mock a collaborator they have no
 *       interest in. With the limiter disabled in {@code application-test.yml}, they see nothing.
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
    name = "rate-limit.guest.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitConfig {

  @Bean
  public GuestRateLimitFilter guestRateLimitFilter(
      StringRedisTemplate redisTemplate, GuestRateLimitProperties properties) {
    return new GuestRateLimitFilter(redisTemplate, properties);
  }

  /**
   * Stops Spring Boot from <em>also</em> registering the filter as a plain servlet filter.
   *
   * <p>Any {@code Filter} bean is auto-registered into the servlet chain, which runs before the
   * security chain. That copy would see an empty security context — so every signed-in user would
   * count as a guest — and would run a second time for requests the security chain already checked.
   * Declaring the registration disabled leaves only the placement inside {@code SecurityConfig}.
   */
  @Bean
  public FilterRegistrationBean<GuestRateLimitFilter> guestRateLimitFilterRegistration(
      GuestRateLimitFilter filter) {
    FilterRegistrationBean<GuestRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
