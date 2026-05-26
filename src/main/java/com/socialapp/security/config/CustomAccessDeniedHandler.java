package com.socialapp.security.config;

import static org.springframework.http.HttpStatus.FORBIDDEN;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
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
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    ErrorDto errorDto =
        new ErrorDto(
            FORBIDDEN.getReasonPhrase() + ": " + accessDeniedException.getMessage(),
            request.getRequestURI(),
            FORBIDDEN);

    try {
      objectMapper.writeValue(response.getWriter(), errorDto);
    } catch (IOException e) {
      log.error("Error writing access denied response", e);
      throw e;
    }
  }
}
