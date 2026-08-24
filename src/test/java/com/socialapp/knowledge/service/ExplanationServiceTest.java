package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.WorkExperience;
import com.socialapp.knowledge.entity.enums.ExplanationStyle;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
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
  @Mock private ExplanationRepository explanationRepository;
  @Mock private UserProfessionalProfileRepository profileRepository;
  @Mock private VaultNoteRepository vaultNoteRepository;
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
            explanationRepository,
            profileRepository,
            vaultNoteRepository,
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
      when(vaultNoteRepository.findByUserIdWithTags(USER_ID)).thenReturn(List.of());
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
      when(vaultNoteRepository.findByUserIdWithTags(USER_ID))
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
                  + " \"https://x\", \"reason\": \"why\"}]}");

      // When
      ExplanationResponseDto result = explanationService.explainPost(USER_ID, POST_ID, null, null);

      // Then
      assertThat(result.getExternalLinks()).hasSize(1);
      assertThat(result.getExternalLinks().get(0).getTitle()).isEqualTo("Doc");
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
