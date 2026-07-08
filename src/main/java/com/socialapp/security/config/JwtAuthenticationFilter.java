package com.socialapp.security.config;

import static org.springframework.http.HttpStatus.FORBIDDEN;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.security.dto.ErrorDto;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtProvider jwtProvider;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = authHeader.substring(7);

    if (jwtProvider.isTokenValid(token)) {
      String email = jwtProvider.extractEmail(token);

      if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);

        if (user != null) {
          if (user.isBanned()) {
            writeBannedResponse(request, response, user);
            return;
          }

          UsernamePasswordAuthenticationToken authToken =
              new UsernamePasswordAuthenticationToken(
                  user,
                  null,
                  java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
          authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authToken);
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  private void writeBannedResponse(
      HttpServletRequest request, HttpServletResponse response, UserEntity user)
      throws IOException {
    response.setStatus(FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    ErrorDto errorDto =
        new ErrorDto(
            "Your account is banned until " + user.getBannedUntil() + ". Please contact support.",
            request.getRequestURI(),
            FORBIDDEN);

    objectMapper.writeValue(response.getWriter(), errorDto);
  }
}
