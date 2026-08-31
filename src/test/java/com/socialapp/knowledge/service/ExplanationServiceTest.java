package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.enums.LearningCategory;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.ratelimit.CostlyOperationProperties;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.VaultContextSettingsEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.WorkExperience;
import com.socialapp.knowledge.entity.enums.ExplanationStyle;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.repository.VaultContextSettingsRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.posts.service.PostVisibilityService;

/**
 * Component (unit) tests for {@link ExplanationService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing — including the
 * exhaustive {@code switch} over {@link ExplanationStyle} in {@code explanationStyleInstruction},
 * Section 2.1.3 BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale. A real
 * {@link ObjectMapper} is used since it only parses literal JSON strings the tests construct.
 */
@ExtendWith(MockitoExtension.class)
class ExplanationServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private GeminiClient geminiClient;
  @Mock private FixedWindowRateLimiter rateLimiter;
  @Mock private ExplanationRepository explanationRepository;
  @Mock private UserProfessionalProfileRepository profileRepository;
  @Mock private VaultNoteRepository vaultNoteRepository;
  @Mock private VaultContextSettingsRepository settingsRepository;
  @Mock private PersonalAccessTokenRepository tokenRepository;
  @Mock private PostRepository postRepository;
  @Mock private PostVisibilityService postVisibilityService;

  private ExplanationService explanationService;

  @Captor private ArgumentCaptor<String> promptCaptor;

  // A real ObjectMapper is used (see class Javadoc), so the service is constructed by hand rather
  // than via @InjectMocks (which would otherwise pass null for it).
  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    explanationService =
        new ExplanationService(
            geminiClient,
            rateLimiter,
            // Mockito's default false from isOverLimit means "budget available", so every existing
            // test keeps its behaviour without stubbing the limiter.
            new CostlyOperationProperties(),
            explanationRepository,
            profileRepository,
            vaultNoteRepository,
            settingsRepository,
            tokenRepository,
            postRepository,
            postVisibilityService,
            new ObjectMapper());
  }

  private static PostEntity post(Integer id, String content) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setContent(content);
    return post;
  }

  private static PostEntity codeSnippetPost(String caption, String language, String code) {
    PostEntity post = post(POST_ID, caption);
    post.setPostType(com.socialapp.posts.entity.enums.PostType.CODE_SNIPPET);
    post.setCodeSnippetDetails(new com.socialapp.posts.entity.CodeSnippetDetails(language, code));
    return post;
  }

  private static UserProfessionalProfileEntity profile(
      ExplanationStyle style, List<WorkExperience> workHistory) {
    UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
    profile.setUserId(USER_ID);
    profile.setJobTitle("Backend Engineer");
    profile.setExplanationStyle(style);
    profile.setWorkHistory(workHistory);
    return profile;
  }

  private void stubHappyPathUpTo(UserProfessionalProfileEntity profile) {
    when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "Post content")));
    when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
    when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile));
    when(tokenRepository.findByUserId(USER_ID)).thenReturn(List.of());
  }

  // =====================================================================
  // explainPost
  // =====================================================================

  @Nested
  @DisplayName("explainPost")
  class ExplainPostTests {

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFound_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> explanationService.explainPost(USER_ID, POST_ID, null, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("404");
    }

    @Test
    @DisplayName("should refuse to explain a post the caller may not read")
    void shouldRefuse_whenThePostIsNotVisibleToTheCaller() {
      // Given: a PRIVATE post belonging to somebody else. Post ids are sequential
      // (PostQueryService says so explicitly), so guessing one is not a barrier.
      PostEntity privatePost = post(POST_ID, "Somebody's private notes");
      privatePost.setAuthorId(4242);
      privatePost.setVisibility(PostVisibility.PRIVATE);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(privatePost));
      when(postVisibilityService.isVisibleTo(privatePost, USER_ID)).thenReturn(false);

      // Deliberately no profile/Gemini stubs: the call must stop at the visibility gate, before
      // anything downstream is consulted. When this probe first ran against the unguarded
      // service it passed for the wrong reason — the 428 "no professional profile" gate fired
      // first — so the stubs had to be added to prove the leak. Now that the gate exists they
      // would be unused, and STRICT_STUBS would rightly complain.

      // When / Then: explainPost returns originalContent verbatim AND ships the body to Gemini,
      // so it must clear the same visibility rule as every other read path
      // (PostVisibilityService.isVisibleTo). It checks existence only — compare
      // PostQueryService.getPost, which 404s in exactly this case.
      assertThatThrownBy(() -> explanationService.explainPost(USER_ID, POST_ID, null, null))
          .isInstanceOfAny(ResponseStatusException.class, ForbiddenException.class);
      verifyNoInteractions(geminiClient);
    }

    @Test
    @DisplayName("should reject when the caller has no professional profile")
    void shouldThrowPreconditionRequired_whenProfileMissing() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> explanationService.explainPost(USER_ID, POST_ID, null, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("428");
    }

    @Test
    @DisplayName("should generate and return an explanation when the profile exists")
    void shouldGenerateExplanation_whenProfileExists() {
      // Given
      stubHappyPathUpTo(profile(ExplanationStyle.CONCISE, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn(
              "{\"explanation\": \"Explained\", \"concepts\": [\"c1\"], \"prerequisites\": [\"p1\"],"
                  + " \"complexityScore\": 4}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getPostId()).isEqualTo(POST_ID);
      assertThat(result.getOriginalContent()).isEqualTo("Post content");
      assertThat(result.getExplanationContent()).isEqualTo("Explained");
      assertThat(result.getConcepts()).containsExactly("c1");
      assertThat(result.getComplexityScore()).isEqualTo(4);
    }

    @Test
    @DisplayName("should send the snippet's code to the model, not just its (often blank) caption")
    void shouldExplainTheCodeOfACodeSnippetPost() {
      // Given — a CODE_SNIPPET whose body lives in the jsonb detail block, with a blank caption,
      // which is the normal shape. Reading only post.getContent() handed Gemini an empty string
      // and the reader got an explanation of nothing.
      when(postRepository.findById(POST_ID))
          .thenReturn(
              Optional.of(codeSnippetPost("", "java", "int add(int a, int b) { return a + b; }")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(List.of());
      when(geminiClient.generateContent(promptCaptor.capture()))
          .thenReturn("{\"explanation\": \"e\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then — the code reaches the prompt, fenced and language-tagged
      assertThat(promptCaptor.getValue())
          .contains("```java")
          .contains("int add(int a, int b) { return a + b; }");
      // Then — and the card's "original" panel is not blank either
      assertThat(result.getOriginalContent()).contains("int add(int a, int b) { return a + b; }");
    }
  }

  // =====================================================================
  // saveExplanation
  // =====================================================================

  @Nested
  @DisplayName("saveExplanation")
  class SaveExplanationTests {

    private SaveExplanationRequestDto request() {
      SaveExplanationRequestDto dto = new SaveExplanationRequestDto();
      dto.setPostId(POST_ID);
      dto.setOriginalContent("orig");
      dto.setExplanationContent("expl");
      dto.setConcepts(List.of("c1"));
      dto.setPrerequisites(List.of("p1"));
      dto.setComplexityScore(3);
      return dto;
    }

    @Test
    @DisplayName("should save as version 1 when there is no previous version")
    void shouldSaveAsVersion1_whenNoPreviousVersion() {
      // Given
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, request());

      // Then
      assertThat(result.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("should persist the external links the client sends back")
    void shouldPersistExternalLinks() {
      // Given — B18: Gemini generated these, the user saw them, and saving dropped them because
      // neither the request DTO nor the entity had anywhere to put them
      SaveExplanationRequestDto dto = request();
      dto.setExternalLinks(
          List.of(
              new ExplanationResponseDto.ExternalLink(
                  "Spring docs", "https://spring.io", "Official reference")));
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, dto);

      // Then
      ArgumentCaptor<ExplanationEntity> saved = ArgumentCaptor.forClass(ExplanationEntity.class);
      verify(explanationRepository).save(saved.capture());
      assertThat(saved.getValue().getExternalLinks())
          .extracting(ExplanationResponseDto.ExternalLink::getUrl)
          .containsExactly("https://spring.io");

      // Then — and echoed straight back, so the card does not blink empty after saving
      assertThat(result.getExternalLinks())
          .extracting(ExplanationResponseDto.ExternalLink::getTitle)
          .containsExactly("Spring docs");
    }

    @Test
    @DisplayName("should save an explanation that has no external links")
    void shouldSaveWithoutExternalLinks() {
      // Given — the model does not always return links
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, request());

      // Then
      assertThat(result.getExternalLinks()).isEmpty();
    }

    @Test
    @DisplayName("should persist the category the client sends back")
    void shouldPersistCategory() {
      // Given: cùng vết xe của externalLinks — model phân loại đúng, người dùng bấm lưu, và nhãn
      // biến mất vì DTO lưu không có chỗ để chứa nó.
      SaveExplanationRequestDto dto = request();
      dto.setCategory(LearningCategory.DEVOPS);
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, dto);

      // Then
      ArgumentCaptor<ExplanationEntity> saved = ArgumentCaptor.forClass(ExplanationEntity.class);
      verify(explanationRepository).save(saved.capture());
      assertThat(saved.getValue().getCategory()).isEqualTo(LearningCategory.DEVOPS);
      assertThat(result.getCategory()).isEqualTo(LearningCategory.DEVOPS);
    }

    @Test
    @DisplayName("should store OTHER when the client sends no category")
    void shouldStoreOtherWhenCategoryOmitted() {
      // Given: cột là NOT NULL ở V77, nên một client cũ không gửi trường này vẫn phải lưu được.
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, request());

      // Then
      assertThat(result.getCategory()).isEqualTo(LearningCategory.OTHER);
    }

    @Test
    @DisplayName("should increment the version when previous versions exist")
    void shouldIncrementVersion_whenPreviousVersionExists() {
      // Given
      when(explanationRepository.findMaxVersion(POST_ID, USER_ID)).thenReturn(Optional.of(3));
      when(explanationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ExplanationResponseDto result = explanationService.saveExplanation(USER_ID, request());

      // Then
      assertThat(result.getVersion()).isEqualTo(4);
    }
  }

  // =====================================================================
  // getLibrary
  // =====================================================================

  @Nested
  @DisplayName("getLibrary")
  class GetLibraryTests {

    @Test
    @DisplayName("should return every explanation mapped, with the total count")
    void shouldReturnMappedLibrary() {
      // Given
      when(explanationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
          .thenReturn(List.of(ExplanationEntity.builder().id(1).postId(POST_ID).build()));

      // When
      KnowledgeLibraryResponseDto result = explanationService.getLibrary(USER_ID);

      // Then
      assertThat(result.getExplanations()).hasSize(1);
      assertThat(result.getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should echo the stored external links on the read path")
    void shouldEchoExternalLinks() {
      // Given — /my-library is where the loss actually showed up: the user goes back to find
      // the links they saw before saving, and toResponseDto was not setting them
      when(explanationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
          .thenReturn(
              List.of(
                  ExplanationEntity.builder()
                      .id(1)
                      .postId(POST_ID)
                      .externalLinks(
                          List.of(
                              new ExplanationResponseDto.ExternalLink(
                                  "Spring docs", "https://spring.io", "Official reference")))
                      .build()));

      // When
      KnowledgeLibraryResponseDto result = explanationService.getLibrary(USER_ID);

      // Then
      assertThat(result.getExplanations().get(0).getExternalLinks())
          .extracting(ExplanationResponseDto.ExternalLink::getUrl)
          .containsExactly("https://spring.io");
    }

    @Test
    @DisplayName("should echo the stored category, which is what the tabs are built from")
    void shouldEchoCategory() {
      // Given: /my-library trả về toàn bộ kho của một người và FE tự gom nhóm, nên nhãn phải đi
      // cùng từng hàng ở CHÍNH danh sách này.
      when(explanationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
          .thenReturn(
              List.of(
                  ExplanationEntity.builder()
                      .id(1)
                      .postId(POST_ID)
                      .category(LearningCategory.QA)
                      .build()));

      // When
      KnowledgeLibraryResponseDto result = explanationService.getLibrary(USER_ID);

      // Then
      assertThat(result.getExplanations().get(0).getCategory()).isEqualTo(LearningCategory.QA);
    }
  }

  // =====================================================================
  // loadVaultContext (indirectly, via explainPost)
  // =====================================================================

  @Nested
  @DisplayName("loadVaultContext (via explainPost)")
  class LoadVaultContextTests {

    private void stubGeminiEcho() {
      when(geminiClient.generateContent(promptCaptor.capture()))
          .thenReturn("{\"explanation\": \"e\"}");
    }

    @Test
    @DisplayName("should omit vault context when the caller has no bidirectional token")
    void shouldOmitVaultContext_whenNoBidirectionalToken() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.WRITE_ONLY)
                      .build()));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).doesNotContain("EXISTING KNOWLEDGE");
    }

    @Test
    @DisplayName("should omit vault context when bidirectional but there are no notes")
    void shouldOmitVaultContext_whenBidirectionalButNoNotes() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.BIDIRECTIONAL)
                      .build()));
      when(vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).thenReturn(List.of());
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).doesNotContain("EXISTING KNOWLEDGE");
    }

    @Test
    @DisplayName("should include vault context when bidirectional and notes exist")
    void shouldIncludeVaultContext_whenBidirectionalAndNotesExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.BIDIRECTIONAL)
                      .build()));
      when(vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
          .thenReturn(
              List.of(
                  VaultNoteEntity.builder()
                      .filename("f.md")
                      .tags(List.of("t"))
                      .links(List.of())
                      .build()));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("EXISTING KNOWLEDGE").contains("f.md");
    }

    @Test
    @DisplayName("should include a note that carries no tags")
    void shouldIncludeUntaggedNote() {
      // Given — REGRESSION. loadVaultContext used to read findByUserIdWithTags, whose
      // `tags IS NOT NULL` clause silently dropped every untagged note. A reader who does not tag
      // — most people, and the plugin does not require it — synced their whole vault and got no
      // context at all, with nothing anywhere saying why.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.BIDIRECTIONAL)
                      .build()));
      when(vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
          .thenReturn(List.of(VaultNoteEntity.builder().filename("untagged.md").build()));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("EXISTING KNOWLEDGE").contains("untagged.md");
    }

    @Test
    @DisplayName("should omit vault context when the caller asked for it to be off")
    void shouldOmitVaultContext_whenCallerOptedOut() {
      // Given — a BIDIRECTIONAL token and notes both exist, so every gate the old code checked
      // says "include"; the request itself is the only thing saying otherwise.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null, false);

      // Then — and it must not have paid for the lookups either
      assertThat(promptCaptor.getValue()).doesNotContain("EXISTING KNOWLEDGE");
      verify(tokenRepository, never()).findByUserId(USER_ID);
      verify(vaultNoteRepository, never()).findByUserIdOrderByUpdatedAtDesc(USER_ID);
    }

    @Test
    @DisplayName("should include vault context when the caller said nothing about it")
    void shouldIncludeVaultContext_whenToggleIsNull() {
      // Given — BVA on the tri-state: null is "no preference" and must keep the old behaviour.
      // Only an explicit false switches the context off.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.BIDIRECTIONAL)
                      .build()));
      when(vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
          .thenReturn(List.of(VaultNoteEntity.builder().filename("kept.md").build()));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("kept.md");
    }

    private void stubVaultWith(VaultNoteEntity... notes) {
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(POST_ID, "content")));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile(null, null)));
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(
              List.of(
                  PersonalAccessTokenEntity.builder()
                      .vaultPermission(VaultPermission.BIDIRECTIONAL)
                      .build()));
      when(vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
          .thenReturn(List.of(notes));
      stubGeminiEcho();
    }

    private static VaultNoteEntity tagged(String filename, String... tags) {
      return VaultNoteEntity.builder().filename(filename).tags(List.of(tags)).build();
    }

    @Test
    @DisplayName("should keep only notes carrying an included tag")
    void shouldApplyIncludeTags() {
      // Given — the plugin pushes a WHOLE vault, journals included; this is the control that
      // lets somebody offer their backend notes without offering their diary
      stubVaultWith(tagged("api.md", "backend"), tagged("diary.md", "daily-log"));
      when(settingsRepository.findById(USER_ID))
          .thenReturn(
              Optional.of(
                  VaultContextSettingsEntity.builder()
                      .userId(USER_ID)
                      .includeTags(List.of("backend"))
                      .excludeTags(List.of())
                      .build()));

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("api.md").doesNotContain("diary.md");
    }

    @Test
    @DisplayName("should let an exclusion beat an inclusion on the same note")
    void shouldLetExcludeWin() {
      // Given — BVA on the precedence rule. A note tagged both ways is the only case where the
      // order of the two lists is observable, and getting it backwards would let a broad include
      // silently override a deliberate exclusion.
      stubVaultWith(tagged("secret.md", "backend", "private"), tagged("api.md", "backend"));
      when(settingsRepository.findById(USER_ID))
          .thenReturn(
              Optional.of(
                  VaultContextSettingsEntity.builder()
                      .userId(USER_ID)
                      .includeTags(List.of("backend"))
                      .excludeTags(List.of("private"))
                      .build()));

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("api.md").doesNotContain("secret.md");
    }

    @Test
    @DisplayName("should treat an empty include list as no restriction")
    void shouldNotBlankTheVaultWhenOnlyExcluding() {
      // Given — configuring ONLY an exclusion must not be read as "include nothing". Getting this
      // wrong empties the whole vault the moment somebody excludes one tag, and the symptom —
      // explanations quietly stop mentioning their notes — has no error message anywhere.
      stubVaultWith(tagged("api.md", "backend"), tagged("diary.md", "daily-log"));
      when(settingsRepository.findById(USER_ID))
          .thenReturn(
              Optional.of(
                  VaultContextSettingsEntity.builder()
                      .userId(USER_ID)
                      .includeTags(List.of())
                      .excludeTags(List.of("daily-log"))
                      .build()));

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("api.md").doesNotContain("diary.md");
    }

    @Test
    @DisplayName("should match tags case-insensitively")
    void shouldFoldCase() {
      // Given — settings are stored lower-cased by VaultNoteService.normalise, but note tags
      // arrive from the plugin exactly as the user typed them in their file
      stubVaultWith(tagged("api.md", "Backend"));
      when(settingsRepository.findById(USER_ID))
          .thenReturn(
              Optional.of(
                  VaultContextSettingsEntity.builder()
                      .userId(USER_ID)
                      .includeTags(List.of("backend"))
                      .excludeTags(List.of())
                      .build()));

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("api.md");
    }
  }

  // =====================================================================
  // buildPrompt (indirectly, via explainPost)
  // =====================================================================

  @Nested
  @DisplayName("buildPrompt (via explainPost)")
  class BuildPromptTests {

    private void stubGeminiEcho() {
      when(geminiClient.generateContent(promptCaptor.capture()))
          .thenReturn("{\"explanation\": \"e\"}");
    }

    @Test
    @DisplayName(
        "should omit the vault section for a non-null but blank vault context — unreachable via"
            + " explainPost() (loadVaultContext() only ever returns null or non-blank), so"
            + " buildPrompt() is invoked directly through reflection to exercise this defensive"
            + " branch on the shared helper")
    void shouldOmitVaultSection_whenVaultContextBlankButNotNull() throws Exception {
      // Given
      java.lang.reflect.Method buildPrompt =
          ExplanationService.class.getDeclaredMethod(
              "buildPrompt",
              String.class,
              UserProfessionalProfileEntity.class,
              String.class,
              String.class,
              String.class);
      buildPrompt.setAccessible(true);

      // When
      String prompt =
          (String)
              buildPrompt.invoke(
                  explanationService, "post content", profile(null, null), null, "   ", null);

      // Then
      assertThat(prompt).doesNotContain("EXISTING KNOWLEDGE");
    }

    @Test
    @DisplayName("should tell the model how to nest lists and not to invent links (B34)")
    void shouldCarryTheMarkdownAndLinkRules() {
      stubGeminiEcho();
      stubHappyPathUpTo(profile(null, null));

      explanationService.explainPost(USER_ID, POST_ID, null, null);

      String prompt = promptCaptor.getValue();
      assertThat(prompt)
          .contains("indent each sub-item by exactly TWO spaces")
          .contains("confident is real and currently")
          .contains("omit a link rather than invent one");
    }

    @Test
    @DisplayName("should list every category constant, so a new one cannot go unasked for")
    void shouldListEveryCategoryInThePrompt() {
      // Given: danh sách trong prompt được sinh từ chính enum. Nếu ai đó gõ tay danh sách vào
      // text block rồi thêm một chủ đề mới mà quên sửa, model sẽ không bao giờ trả về chủ đề đó
      // và mọi bài thuộc về nó lặng lẽ rơi vào OTHER. Bài kiểm thử này là thứ phát hiện ra.
      stubGeminiEcho();
      stubHappyPathUpTo(profile(null, null));

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      String prompt = promptCaptor.getValue();
      for (LearningCategory category : LearningCategory.values()) {
        assertThat(prompt).contains(category.name());
      }
    }

    @Test
    @DisplayName("should include each work-history domain when present")
    void shouldIncludeWorkHistoryDomains_whenPresent() {
      // Given
      WorkExperience experience = new WorkExperience("Acme", "Fintech", "Dev", 24);
      stubHappyPathUpTo(profile(null, List.of(experience)));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue()).contains("Fintech");
    }

    @Test
    @DisplayName("should not fail when work history is null")
    void shouldOmitWorkHistoryDomains_whenNull() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When / Then
      assertThat(explanationService.explainPost(USER_ID, POST_ID, null, null)).isNotNull();
    }

    @Test
    @DisplayName("should use the CONCISE style instruction")
    void shouldUseConciseInstruction() {
      stubHappyPathUpTo(profile(ExplanationStyle.CONCISE, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).contains("CONCISE: keep the explanation short");
    }

    @Test
    @DisplayName("should use the DETAILED style instruction")
    void shouldUseDetailedInstruction() {
      stubHappyPathUpTo(profile(ExplanationStyle.DETAILED, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).contains("DETAILED: thorough explanation");
    }

    @Test
    @DisplayName("should use the CODE_HEAVY style instruction")
    void shouldUseCodeHeavyInstruction() {
      stubHappyPathUpTo(profile(ExplanationStyle.CODE_HEAVY, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).contains("CODE_HEAVY: prefer runnable code snippets");
    }

    @Test
    @DisplayName("should use the ANALOGY_HEAVY style instruction")
    void shouldUseAnalogyHeavyInstruction() {
      stubHappyPathUpTo(profile(ExplanationStyle.ANALOGY_HEAVY, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).contains("ANALOGY_HEAVY: lean on real-world analogies");
    }

    @Test
    @DisplayName("should fall back to a neutral instruction when no style is set")
    void shouldUseDefaultInstruction_whenStyleNull() {
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).contains("no preference stated");
    }

    @Test
    @DisplayName("should include the reader's feedback note when present and non-blank")
    void shouldIncludeFeedbackNote_whenPresentAndNonBlank() {
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, "too vague last time", null);
      assertThat(promptCaptor.getValue())
          .contains("READER FEEDBACK")
          .contains("too vague last time");
    }

    @Test
    @DisplayName("should omit the feedback section when the note is null")
    void shouldOmitFeedbackNote_whenNull() {
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, null);
      assertThat(promptCaptor.getValue()).doesNotContain("READER FEEDBACK");
    }

    @Test
    @DisplayName("should omit the feedback section when the note is blank")
    void shouldOmitFeedbackNote_whenBlank() {
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, "   ", null);
      assertThat(promptCaptor.getValue()).doesNotContain("READER FEEDBACK");
    }

    @Test
    @DisplayName("should tell the model to answer in the language the reader asked for")
    void shouldPinTheAnswerToTheRequestedLanguage() {
      // Given — the reader is reading the app in Vietnamese
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, "vi");

      // Then — the JDK's own name for the language, not the tag the caller sent
      assertThat(promptCaptor.getValue()).contains("entirely in Vietnamese");
    }

    @Test
    @DisplayName("should replace the match-the-post rule rather than stack a second one on top")
    void shouldReplaceRuleSix_whenALanguageIsGiven() {
      // Given — two instructions that can disagree ("match the post" and "answer in Vietnamese")
      // leave the model to choose, and it chose the post often enough that the reader's setting
      // looked ignored at random
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, "vi");

      // Then
      assertThat(promptCaptor.getValue())
          .doesNotContain("Respond in the same language as the original post");
    }

    @Test
    @DisplayName("should keep the match-the-post rule when no language is stated")
    void shouldKeepRuleSix_whenNoLanguageIsGiven() {
      // Given — the field is optional, and absent must mean the old behaviour rather than a guess
      // made on the caller's behalf
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(promptCaptor.getValue())
          .contains("Respond in the same language as the original post");
    }

    @Test
    @DisplayName("should treat a blank language as no language at all")
    void shouldIgnoreABlankLanguage() {
      // Given — a client sending an empty locale cookie should not produce "answer entirely in "
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, "   ");

      // Then
      assertThat(promptCaptor.getValue())
          .contains("Respond in the same language as the original post");
    }

    @Test
    @DisplayName("should resolve a regional tag to its full name")
    void shouldResolveARegionalTag() {
      // Given / When — EP: BCP-47 allows a region subtag, and the client may well send one
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();
      explanationService.explainPost(USER_ID, POST_ID, null, "en-GB");

      // Then
      assertThat(promptCaptor.getValue()).contains("English (United Kingdom)");
    }

    @Test
    @DisplayName("should pass a well-formed tag the JDK cannot name straight through")
    void shouldPassThroughAnUnknownTag() {
      // Given — the pattern on the DTO has already limited this to letters, digits and hyphens,
      // so nothing that could steer the model survives that far; refusing the request over a
      // language nobody can name would be the worse answer
      stubHappyPathUpTo(profile(null, null));
      stubGeminiEcho();

      // When
      explanationService.explainPost(USER_ID, POST_ID, null, "zz");

      // Then
      assertThat(promptCaptor.getValue()).contains("entirely in zz");
    }
  }

  // =====================================================================
  // parseGeminiResponse (indirectly, via explainPost)
  // =====================================================================

  @Nested
  @DisplayName("parseGeminiResponse (via explainPost)")
  class ParseGeminiResponseTests {

    @Test
    @DisplayName("should parse external links when the array is present and non-empty")
    void shouldParseExternalLinks_whenArrayNonEmpty() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn(
              "{\"explanation\": \"e\", \"externalLinks\": [{\"title\": \"Doc\", \"url\":"
                  + " \"https://spring.io\", \"reason\": \"why\"}]}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExternalLinks()).hasSize(1);
      assertThat(result.getExternalLinks().get(0).getTitle()).isEqualTo("Doc");
    }

    @Test
    @DisplayName("should drop an external link the model corrupted mid-generation (B34)")
    void shouldDropCorruptedExternalLink() {
      // Given — two links: one clean, one with non-Latin script spliced into the path (the exact
      // failure measured 31/08/2026) and one more that is a bare word host. Only the clean one is
      // followable.
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn(
              "{\"explanation\": \"e\", \"externalLinks\": ["
                  + "{\"title\": \"Real\", \"url\": \"https://docs.oracle.com/en/java/\"},"
                  + "{\"title\": \"Glitched\", \"url\":"
                  + " \"https://datadoghq.com/blog/observability-படு/x\"},"
                  + "{\"title\": \"Dotless\", \"url\": \"https://localhost/guide\"}]}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExternalLinks())
          .extracting(ExplanationResponseDto.ExternalLink::getTitle)
          .containsExactly("Real");
    }

    @Test
    @DisplayName("should read the category the model chose")
    void shouldParseCategory() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn("{\"explanation\": \"e\", \"category\": \"SECURITY\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getCategory()).isEqualTo(LearningCategory.SECURITY);
    }

    @Test
    @DisplayName("should keep OTHER when the model answers with a category that does not exist")
    void shouldFallBackToOtherCategory_whenUnknown() {
      // Given: một nhãn lạ chỉ làm hỏng cái tab. Ném lỗi ở đây sẽ vứt bỏ cả bản giải thích —
      // thứ người dùng đang chờ và đã trả tiền model để có.
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn("{\"explanation\": \"e\", \"category\": \"BLOCKCHAIN\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExplanationContent()).isEqualTo("e");
      assertThat(result.getCategory()).isEqualTo(LearningCategory.OTHER);
    }

    @Test
    @DisplayName("should keep OTHER when the model omits the category entirely")
    void shouldDefaultCategoryToOther_whenMissing() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString())).thenReturn("{\"explanation\": \"e\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getCategory()).isEqualTo(LearningCategory.OTHER);
    }

    @Test
    @DisplayName("should default the complexity score to 3 when missing")
    void shouldDefaultComplexityScoreTo3_whenMissing() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString())).thenReturn("{\"explanation\": \"e\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getComplexityScore()).isEqualTo(3);
    }

    @Test
    @DisplayName("should omit external links when the array is empty")
    void shouldOmitExternalLinks_whenArrayEmpty() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn("{\"explanation\": \"e\", \"externalLinks\": []}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExternalLinks()).isEmpty();
    }

    @Test
    @DisplayName("should omit external links when the field is not an array")
    void shouldOmitExternalLinks_whenNotArray() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString()))
          .thenReturn("{\"explanation\": \"e\", \"externalLinks\": \"none\"}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExternalLinks()).isEmpty();
    }

    @Test
    @DisplayName("should fall back to the raw response text when it is not valid JSON")
    void shouldFallBackToRawText_whenResponseInvalidJson() {
      // Given
      stubHappyPathUpTo(profile(null, null));
      when(geminiClient.generateContent(anyString())).thenReturn("not json at all");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExplanationContent()).isEqualTo("not json at all");
      assertThat(result.getComplexityScore()).isEqualTo(3);
      assertThat(result.getConcepts()).isEmpty();
    }
  }
}
