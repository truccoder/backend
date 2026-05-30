package com.socialapp.security.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomAuthenticationEntryPoint authenticationEntryPoint;
  private final CustomAccessDeniedHandler accessDeniedHandler;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/v1/api/auth/**")
                    .permitAll()
                    .requestMatchers("/v1/api/friendships/**")
                    .permitAll()
                    .requestMatchers("/v1/api/feed/**")
                    .permitAll()
                    .requestMatchers("/v1/api/posts/**")
                    .permitAll()
                    .requestMatchers("/v1/api/events/**")
                    .permitAll()
                    .requestMatchers("/v1/api/search/**")
                    .permitAll()
                    .requestMatchers("/v1/api/knowledge/**")
                    .permitAll()
                    .requestMatchers("/v1/api/profile/**")
                    .permitAll()
                    .requestMatchers("/v1/api/tokens/**")
                    .permitAll()
                    .requestMatchers("/v1/api/trending/**")
                    .permitAll()
                    .requestMatchers("/v1/api/books/**")
                    .permitAll()
                    .requestMatchers("/v1/api/payments/**")
                    .permitAll()
                    .requestMatchers("/v1/api/notifications/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
