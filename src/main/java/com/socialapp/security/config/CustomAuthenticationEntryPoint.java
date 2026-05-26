package com.socialapp.security.config;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.security.dto.ErrorDto;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException, ServletException {

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    ErrorDto errorDto =
        new ErrorDto(
            UNAUTHORIZED.getReasonPhrase() + ": " + authException.getMessage(),
            request.getRequestURI(),
            UNAUTHORIZED);

    try {
      objectMapper.writeValue(response.getWriter(), errorDto);
    } catch (IOException e) {
      log.error("Error writing authentication error response", e);
      throw e;
    }
  }
}
