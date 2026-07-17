package com.socialapp.security.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.github.service.GithubApiClient;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.dto.OAuthUrlResponseDto;
import com.socialapp.security.entity.AuthProvider;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.exception.AccountBannedException;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.util.EmailNormalizer;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OAuthAuthService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final TokenService tokenService;
  private final GoogleApiClient googleApiClient;
  private final GithubApiClient githubApiClient;

  public OAuthUrlResponseDto getGoogleOAuthUrl() {
    return OAuthUrlResponseDto.builder().oauthUrl(googleApiClient.getOAuthUrl()).build();
  }

  @Transactional
  public AuthResponseDto loginWithGoogle(String code) {
    String accessToken = googleApiClient.exchangeCodeForToken(code);
    JsonNode userInfo = googleApiClient.getUserInfo(accessToken);

    if (!userInfo.has("email")) {
      throw new ValidationException("Failed to retrieve email from Google");
    }

    String email = EmailNormalizer.normalize(userInfo.get("email").asText());
    String name = userInfo.has("name") ? userInfo.get("name").asText() : "";
    String picture = userInfo.has("picture") ? userInfo.get("picture").asText() : "";
    String sub = userInfo.has("sub") ? userInfo.get("sub").asText() : "";

    boolean isNewUser = false;
    boolean isAutoLinked = false;
    UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);

    if (user == null) {
      isNewUser = true;
      user = new UserEntity();
      user.setEmail(email);
      user.setFullName(name);
      user.setUsername(generateUsername(email));
      user.setProfilePictureUrl(picture);
      user.setEmailVerified(true);
      user.setAuthProvider(AuthProvider.GOOGLE);
      user.setProviderId(sub);

      user = userRepository.save(user);
    } else {
      if (user.isBanned()) {
        throw new AccountBannedException(user.getBannedUntil());
      }

      if (user.getAuthProvider() == AuthProvider.LOCAL) {
        isAutoLinked = true;
        user.setAuthProvider(AuthProvider.GOOGLE);
        user.setProviderId(sub);
        userRepository.save(user);
      }
    }

    return tokenService.issueTokens(user, isAutoLinked, isNewUser);
  }

  public OAuthUrlResponseDto getGithubOAuthUrl() {
    return OAuthUrlResponseDto.builder().oauthUrl(githubApiClient.getOAuthUrl()).build();
  }

  @Transactional
  public AuthResponseDto loginWithGithub(String code) {
    String accessToken = githubApiClient.exchangeCodeForToken(code);
    JsonNode githubUser = githubApiClient.getAuthenticatedUser(accessToken);

    String email = null;
    JsonNode emailsNode = githubApiClient.getUserEmails(accessToken);
    if (emailsNode != null && emailsNode.isArray()) {
      for (JsonNode emailNode : emailsNode) {
        if (emailNode.has("primary")
            && emailNode.get("primary").asBoolean()
            && emailNode.has("verified")
            && emailNode.get("verified").asBoolean()) {
          email = emailNode.get("email").asText();
          break;
        }
      }
    }

    if (email == null) {
      throw new ValidationException("Failed to retrieve a verified primary email from GitHub");
    }

    email = EmailNormalizer.normalize(email);
    String name =
        githubUser.has("name") && !githubUser.get("name").isNull()
            ? githubUser.get("name").asText()
            : "";
    String picture = githubUser.has("avatar_url") ? githubUser.get("avatar_url").asText() : "";
    String sub = githubUser.has("id") ? githubUser.get("id").asText() : "";

    boolean isNewUser = false;
    boolean isAutoLinked = false;
    UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);

    if (user == null) {
      isNewUser = true;
      user = new UserEntity();
      user.setEmail(email);
      user.setFullName(name);
      user.setUsername(generateUsername(email));
      user.setProfilePictureUrl(picture);
      user.setEmailVerified(true);
      user.setAuthProvider(AuthProvider.GITHUB);
      user.setProviderId(sub);

      user = userRepository.save(user);
    } else {
      if (user.isBanned()) {
        throw new AccountBannedException(user.getBannedUntil());
      }
      if (user.getAuthProvider() == AuthProvider.LOCAL) {
        isAutoLinked = true;
        user.setAuthProvider(AuthProvider.GITHUB);
        user.setProviderId(sub);
        userRepository.save(user);
      }
    }

    // KHÔNG tự động đồng bộ (sync) dữ liệu GitHub Stats ở đây nữa để tối ưu UX và cho phép user chủ
    // động. Nếu muốn liên kết, user sẽ bấm nút từ Profile sau.

    return tokenService.issueTokens(user, isAutoLinked, isNewUser);
  }

  private String generateUsername(String email) {
    String baseUsername = email.split("@")[0].replaceAll("[^a-zA-Z0-9]", "");
    return baseUsername + "_" + SECURE_RANDOM.nextInt(10000);
  }
}
