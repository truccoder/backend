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
import com.socialapp.moderation.service.BanDetailsService;
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
  private final BanDetailsService banDetailsService;

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
          if (user.isBanned() && !isBannedUserAllowed(request)) {
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

  /**
   * The whole appeal surface, and nothing else: read what you were sanctioned for, dispute it, and
   * find out how it went.
   *
   * <p>Without this exemption the appeal flow could not be used by the people it is for. The ban
   * check runs before routing and refuses every request, so a banned user hitting {@code POST
   * /v1/api/moderation/appeals} would get the same 403 telling them to contact a support channel
   * that does not exist — the appeal endpoint would exist and be unreachable by anyone with a
   * reason to call it. Reading the outcome is exempt for the same reason: being told "you may
   * appeal" and then not being allowed to see the answer is the same dead end one step later.
   *
   * <p>Narrow on purpose: exact path matches, not a prefix, and the ban still applies to every
   * other endpoint. Deliberately method-blind — {@code /v1/api/moderation/appeals} is exempt for
   * both {@code POST} (file one) and {@code GET} (read your own), which are the only two methods
   * mapped on it. The admin side lives under {@code /v1/api/admin/**} and is not listed here, so a
   * banned admin cannot review their own case.
   *
   * <p><b>Known gap, not an oversight.</b> {@code AuthService.login} still refuses a banned account
   * outright, so this only helps someone whose token was issued before the ban and has not yet
   * expired. Letting a banned account log in — authentication succeeding while authorisation stays
   * revoked — is the change that would close it, and it alters a documented login contract, so it
   * is recorded in the debt ledger rather than made here.
   */
  private boolean isBannedUserAllowed(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return "/v1/api/moderation/appeals".equals(uri)
        || "/v1/api/moderation/my-violations".equals(uri);
  }

  /**
   * The 403 a banned account gets on every request it makes with a still-valid token.
   *
   * <p>The prose sentence is unchanged and still the {@code message}, but it is no longer the only
   * place the end date appears: {@code banDetails} carries {@code bannedUntil} as an {@code
   * OffsetDateTime}, plus the violation type and reason. A client that wanted to show "unlocked in
   * 3 days" previously had to pull a timestamp out of an English sentence — which breaks the moment
   * the sentence is translated or reworded.
   *
   * <p>This runs in a filter, outside {@code GlobalExceptionHandler}, which is why the same
   * enrichment had to be done twice; the two are kept in step by both calling {@code
   * BanDetailsService}.
   */
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
            FORBIDDEN,
            banDetailsService.describe(user.getId(), user.getBannedUntil()));

    objectMapper.writeValue(response.getWriter(), errorDto);
  }
}
