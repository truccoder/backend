package com.socialapp.security.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.socialapp.common.ratelimit.GuestRateLimitFilter;
import com.socialapp.common.ratelimit.GuestRateLimitProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({
  JwtProperties.class,
  CorsProperties.class,
  GuestRateLimitProperties.class
})
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomAuthenticationEntryPoint authenticationEntryPoint;
  private final CustomAccessDeniedHandler accessDeniedHandler;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final ObjectProvider<GuestRateLimitFilter> guestRateLimitFilter;
  private final CorsProperties corsProperties;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    HttpSecurity chain =
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(
                requests ->
                    requests
                        .requestMatchers("/v1/api/auth/**")
                        .permitAll()
                        .requestMatchers("/v1/api/knowledge/sync/**")
                        .permitAll()
                        .requestMatchers("/v1/api/payments/momo/webhook")
                        .permitAll()
                        // Google's OAuth consent screen redirects the browser directly to this URL
                        // with no way to attach this app's own bearer token, so it has to stay
                        // open.
                        // Authorisation is enforced inside the handler instead: "state" is a
                        // single-use server-issued nonce that maps back to the user who started the
                        // flow (GoogleCalendarService#consumeOAuthState). It is NOT the user id —
                        // it used to be, which made this endpoint an account-takeover vector.
                        .requestMatchers("/v1/api/events/google/callback")
                        .permitAll()
                        // Swagger UI fetches the spec before any login happens, so both paths
                        // have to be open. Nothing is exposed when SPRINGDOC_ENABLED=false: the
                        // handlers aren't registered at all, so these matchers hit a 404.
                        .requestMatchers(
                            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // ---- Guest-readable surface -------------------------------------------
                        // The product opened its read side to visitors who have not signed in: the
                        // discovery feed, a single public post, a public profile and its posts,
                        // plus
                        // the profile sections that were already relationship-free.
                        //
                        // Every entry is pinned to GET and to a single path segment, deliberately.
                        // One tempting ".requestMatchers(\"/v1/api/posts/**\")" would also open
                        // POST /v1/api/posts (anonymous posting), DELETE of any post, and the
                        // nested
                        // /comments and /reactions collections — none of which a guest may touch.
                        //
                        // What is NOT here is as load-bearing as what is: search, roadmaps,
                        // anything
                        // personalised, and every write. /v1/api/feed in particular cannot be
                        // opened
                        // even if someone wanted to — it is a per-user Redis fan-out list, so a
                        // guest
                        // has none and would get an empty page; their home is /v1/api/posts/public.
                        //
                        // These endpoints are additionally IP rate-limited for anonymous callers,
                        // see
                        // GuestRateLimitFilter — a public read surface with no per-user identity to
                        // throttle is otherwise a bulk-download endpoint.
                        .requestMatchers(HttpMethod.GET, "/v1/api/posts/public")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/posts/{postId:\\d+}")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/users/*/profile")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/users/*/posts")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/users/*/reputation")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/github/stats/*")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/books/author/*")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/api/trending")
                        .permitAll()
                        // ---- end guest-readable surface ---------------------------------------
                        .requestMatchers("/v1/api/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
            .exceptionHandling(
                exception ->
                    exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    // After the JWT filter, not before: the limiter decides whether to throttle by asking whether
    // the caller is signed in, and before the JWT filter runs the security context is still empty,
    // so every request — authenticated or not — would look like a guest.
    //
    // ObjectProvider because the limiter is optional: it exists only when rate-limit.guest.enabled
    // is on (see RateLimitConfig). ifAvailable, not orElseThrow, so switching it off is a config
    // change rather than a startup failure.
    guestRateLimitFilter.ifAvailable(
        filter -> chain.addFilterAfter(filter, JwtAuthenticationFilter.class));

    return chain.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
