package com.socialapp.knowledge.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.ratelimit.CostlyOperationProperties;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.enums.ExplanationStyle;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.posts.service.PostVisibilityService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExplanationService {

  private static final String AI_RATE_LIMIT_KEY_PREFIX = "ratelimit:ai:explain:";

  /** How many vault notes may be summarised into one prompt — see {@code loadVaultContext}. */
  private static final int MAX_VAULT_NOTES_IN_CONTEXT = 50;

  private final GeminiClient geminiClient;
  private final FixedWindowRateLimiter rateLimiter;
  private final CostlyOperationProperties costlyOperationProperties;
  private final ExplanationRepository explanationRepository;
  private final UserProfessionalProfileRepository profileRepository;
  private final VaultNoteRepository vaultNoteRepository;
  private final PersonalAccessTokenRepository tokenRepository;
  private final PostRepository postRepository;
  private final PostVisibilityService postVisibilityService;
  private final ObjectMapper objectMapper;

  /**
   * Generate explanation without saving. Returns result for user to decide whether to save.
   * Throws 428 if professional profile is not set up.
   *
   * <p><b>Deliberately not {@code @Transactional}.</b> The slow part of this method is {@code
   * geminiClient.generateContent}, seconds of waiting on somebody else's model, and a transaction
   * opened around the reads above it would hold its Hikari connection for that whole wait — a
   * handful of concurrent explanations is then enough to starve the pool for every other request
   * in the app. Nothing here needs one: the three reads are independent lookups, none of them
   * needs a shared snapshot, each repository call is transactional on its own, and neither the
   * visibility check nor the vault context touches a LAZY association — {@code
   * PostVisibilityService.isVisibleTo} reads scalar columns, and {@code VaultNoteEntity.tags} and
   * {@code links} are jsonb columns that arrive with the row.
   */
  public ExplanationResponseDto explainPost(
      Integer userId, Integer postId, String feedbackNote, String language) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found: " + postId));

    // This method returns the post body verbatim as originalContent AND sends it to Gemini, so it
    // has to clear the same rule as every other read path. Existence alone was not enough: post
    // ids are sequential (see PostQueryService), so any signed-in user could walk them and read
    // the full text of PRIVATE, FRIENDS-only, PENDING or REJECTED posts — and have a third party
    // read them too. Same 404-not-403 choice as PostReactionService#requireVisiblePost: a post you
    // may not read must not be distinguishable from one that does not exist.
    if (!postVisibilityService.isVisibleTo(post, userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found: " + postId);
    }

    UserProfessionalProfileEntity profile = profileRepository.findById(userId).orElse(null);
    if (Objects.isNull(profile)) {
      throw new ResponseStatusException(
          HttpStatus.PRECONDITION_REQUIRED,
          "Professional profile required. Please set up your profile first.");
    }

    requireAiBudget(userId);

    String vaultContext = loadVaultContext(userId);
    String prompt = buildPrompt(post.getContent(), profile, feedbackNote, vaultContext, language);
    String geminiResponse = geminiClient.generateContent(prompt);
    GeminiExplanationResult parsed = parseGeminiResponse(geminiResponse);

    return ExplanationResponseDto.builder()
        .postId(postId)
        .originalContent(post.getContent())
        .explanationContent(parsed.explanation)
        .concepts(parsed.concepts)
        .prerequisites(parsed.prerequisites)
        .complexityScore(parsed.complexityScore)
        .externalLinks(parsed.externalLinks)
        .build();
  }

  @Transactional
  public ExplanationResponseDto saveExplanation(Integer userId, SaveExplanationRequestDto request) {
    int nextVersion =
        explanationRepository.findMaxVersion(request.getPostId(), userId).orElse(0) + 1;

    ExplanationEntity entity =
        ExplanationEntity.builder()
            .postId(request.getPostId())
            .userId(userId)
            .originalContent(request.getOriginalContent())
            .explanationContent(request.getExplanationContent())
            .concepts(request.getConcepts())
            .prerequisites(request.getPrerequisites())
            .externalLinks(request.getExternalLinks())
            .complexityScore(request.getComplexityScore())
            .version(nextVersion)
            .build();

    explanationRepository.save(entity);
    return toResponseDto(entity);
  }

  public KnowledgeLibraryResponseDto getLibrary(Integer userId) {
    List<ExplanationEntity> explanations =
        explanationRepository.findByUserIdOrderByCreatedAtDesc(userId);

    List<ExplanationResponseDto> dtos = explanations.stream().map(this::toResponseDto).toList();

    return KnowledgeLibraryResponseDto.builder().explanations(dtos).totalCount(dtos.size()).build();
  }

  /**
   * Refuses the call when this user has spent their AI budget for the window.
   *
   * <p>Keyed on the user rather than the IP: the endpoint requires a session, so the account is the
   * accountable thing, and an IP key would let one person spread a loop across networks while
   * punishing everyone behind a shared one.
   *
   * <p>Uses the same {@code FixedWindowRateLimiter} as the auth and guest limiters — including its
   * fail-open behaviour, so a Redis outage degrades the ceiling rather than blocking study.
   */
  private void requireAiBudget(Integer userId) {
    if (!costlyOperationProperties.isEnabled()) {
      return;
    }
    boolean overLimit =
        rateLimiter.isOverLimit(
            AI_RATE_LIMIT_KEY_PREFIX + userId,
            costlyOperationProperties.getAiRequests(),
            costlyOperationProperties.getAiWindow());
    if (overLimit) {
      log.warn("AI explanation budget exhausted for user {}", userId);
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "You have requested a lot of explanations recently. Please try again later.");
    }
  }

  private String loadVaultContext(Integer userId) {
    boolean hasBidirectionalAccess =
        tokenRepository.findByUserId(userId).stream()
            .anyMatch(t -> VaultPermission.BIDIRECTIONAL.equals(t.getVaultPermission()));

    if (!hasBidirectionalAccess) {
      return null;
    }

    List<VaultNoteEntity> notes = vaultNoteRepository.findByUserIdWithTags(userId);
    if (notes.isEmpty()) {
      return null;
    }

    // Capped. Every note here goes into the prompt of every /explain call, so an untrimmed vault
    // multiplies the token cost of each request by its own size and eventually overruns the model's
    // context window outright. Only filenames, tags and links are sent — not note bodies — so
    // taking a slice costs little context and bounds the cost.
    if (notes.size() > MAX_VAULT_NOTES_IN_CONTEXT) {
      notes = notes.subList(0, MAX_VAULT_NOTES_IN_CONTEXT);
    }

    return notes.stream()
        .map(
            n ->
                "- Note: "
                    + n.getFilename()
                    + " | Tags: "
                    + n.getTags()
                    + " | Links: "
                    + n.getLinks())
        .collect(Collectors.joining("\n"));
  }

  private String buildPrompt(
      String postContent,
      UserProfessionalProfileEntity profile,
      String feedbackNote,
      String vaultContext,
      String language) {
    StringBuilder sb = new StringBuilder();

    sb.append(
        "You are an expert technical educator. Your task is to help a reader understand a social"
            + " media post written by a senior tech leader (CEO/CTO/Staff Engineer).\n\n");

    sb.append("=== STRICT RULES ===\n");
    sb.append("1. NEVER modify, summarize, or rephrase the original post content\n");
    sb.append("2. ONLY provide explanatory annotations as SEPARATE content\n");
    sb.append("3. Each annotation must reference which part of the original it explains\n");
    sb.append("4. Use analogies appropriate for the reader's experience level\n");
    sb.append("5. If a concept has prerequisites, list them explicitly\n");
    String targetLanguage = resolveLanguage(language);
    if (Objects.isNull(targetLanguage)) {
      sb.append("6. Respond in the same language as the original post\n");
    } else {
      // Overrides rule 6 rather than being appended after it: two instructions that can disagree
      // ("match the post" and "answer in Vietnamese") leave the model to pick, and it picked the
      // post's language often enough that the reader's choice looked ignored at random.
      sb.append("6. Write EVERY field of your response entirely in ")
          .append(targetLanguage)
          .append(", whatever language the original post is written in. Do not translate the")
          .append(" original post itself — it is quoted below for reference only.\n");
    }
    sb.append("7. Include 2-5 external links (blog posts, docs, videos) for deeper learning\n\n");

    sb.append("=== READER PROFILE ===\n");
    sb.append("- Job title: ").append(profile.getJobTitle()).append("\n");
    sb.append("- Primary role: ").append(profile.getPrimaryRole()).append("\n");
    sb.append("- Seniority: ").append(profile.getSeniorityLevel()).append("\n");
    sb.append("- Years of experience: ").append(profile.getYearsOfExperience()).append("\n");
    sb.append("- Known tech stack: ").append(profile.getKnownTechStack()).append("\n");
    sb.append("- Work domains: ");
    if (profile.getWorkHistory() != null) {
      profile.getWorkHistory().forEach(w -> sb.append(w.getDomain()).append(", "));
    }
    sb.append("\n");
    sb.append("- Interested in: ").append(profile.getInterestedDomains()).append("\n");
    sb.append("- Preferred explanation style: ")
        .append(
            profile.getExplanationStyle() != null
                ? explanationStyleInstruction(profile.getExplanationStyle())
                : "no preference stated, use your best judgement")
        .append("\n\n");

    if (Objects.nonNull(vaultContext) && !vaultContext.isBlank()) {
      sb.append("=== READER'S EXISTING KNOWLEDGE (from their personal vault/notes) ===\n");
      sb.append(vaultContext).append("\n");
      sb.append(
          "Use this context to avoid re-explaining concepts they already know."
              + " Reference their existing notes when relevant.\n\n");
    }

    if (feedbackNote != null && !feedbackNote.isBlank()) {
      sb.append("=== READER FEEDBACK (previous explanation was not clear enough) ===\n");
      sb.append(feedbackNote).append("\n\n");
    }

    sb.append("=== ORIGINAL POST ===\n");
    sb.append(postContent).append("\n\n");

    sb.append("=== RESPONSE FORMAT (JSON) ===\n");
    sb.append(
        """
        {
          "explanation": "Your detailed explanation with context, analogies, and breakdown. Use markdown formatting.",
          "concepts": ["concept1", "concept2"],
          "prerequisites": ["prerequisite knowledge 1", "prerequisite knowledge 2"],
          "complexityScore": 3,
          "externalLinks": [
            {"title": "Resource title", "url": "https://...", "reason": "Why this helps"}
          ]
        }

        Where complexityScore is 1-5 (1=beginner friendly, 5=very advanced).
        The "explanation" field should be comprehensive and directly help the reader understand the post without modifying the original meaning.
        externalLinks should be real, reputable URLs (official docs, well-known blogs, conference talks).
        """);

    return sb.toString();
  }

  /**
   * Turns a caller-supplied language tag into a language name the prompt can use, or {@code null}
   * when the caller stated nothing usable.
   *
   * <p>The tag is resolved through {@link Locale} and it is the JDK's <em>English display name</em>
   * that goes into the prompt, never the caller's own text. That is the second half of the
   * validation on {@code ExplainRequestDto.language}: even a tag that satisfies the pattern only
   * reaches Gemini as a word taken from the JDK's language table.
   *
   * <p>A well-formed tag the JDK has no name for ({@code "zz"}) comes back as the tag itself. That
   * is left alone rather than rejected: the pattern has already limited it to letters, digits and
   * hyphens, so nothing dangerous survives that far, and a request naming a language nobody can
   * name is not worth a 400 when the model will make a reasonable job of a bare tag.
   */
  private String resolveLanguage(String language) {
    if (Objects.isNull(language) || language.isBlank()) {
      return null;
    }
    String displayName = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH);
    return displayName.isBlank() ? null : displayName;
  }

  private String explanationStyleInstruction(ExplanationStyle style) {
    return switch (style) {
      case CONCISE -> "CONCISE: keep the explanation short, bullet points over prose";
      case DETAILED -> "DETAILED: thorough explanation, cover edge cases and nuance";
      case CODE_HEAVY -> "CODE_HEAVY: prefer runnable code snippets over prose descriptions";
      case ANALOGY_HEAVY -> "ANALOGY_HEAVY: lean on real-world analogies before technical detail";
    };
  }

  private GeminiExplanationResult parseGeminiResponse(String response) {
    try {
      JsonNode root = objectMapper.readTree(response);

      String explanation = root.path("explanation").asText("");
      List<String> concepts =
          objectMapper.convertValue(
              root.path("concepts"),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
      List<String> prerequisites =
          objectMapper.convertValue(
              root.path("prerequisites"),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
      int complexityScore = root.path("complexityScore").asInt(3);

      List<ExplanationResponseDto.ExternalLink> externalLinks = List.of();
      JsonNode linksNode = root.path("externalLinks");
      if (linksNode.isArray() && !linksNode.isEmpty()) {
        externalLinks =
            objectMapper.convertValue(
                linksNode,
                objectMapper
                    .getTypeFactory()
                    .constructCollectionType(
                        List.class, ExplanationResponseDto.ExternalLink.class));
      }

      return new GeminiExplanationResult(
          explanation, concepts, prerequisites, complexityScore, externalLinks);
    } catch (Exception e) {
      log.warn("Failed to parse structured Gemini response, using raw text: {}", e.getMessage());
      return new GeminiExplanationResult(response, List.of(), List.of(), 3, List.of());
    }
  }

  private ExplanationResponseDto toResponseDto(ExplanationEntity entity) {
    return ExplanationResponseDto.builder()
        .id(entity.getId())
        .postId(entity.getPostId())
        .originalContent(entity.getOriginalContent())
        .explanationContent(entity.getExplanationContent())
        .concepts(entity.getConcepts())
        .prerequisites(entity.getPrerequisites())
        // Echoed on the read path too, not only right after saving: /my-library was the place
        // the loss actually showed up, since that is where the user goes back to find them.
        .externalLinks(entity.getExternalLinks())
        .complexityScore(entity.getComplexityScore())
        .version(entity.getVersion())
        .createdAt(entity.getCreatedAt())
        .build();
  }

  private record GeminiExplanationResult(
      String explanation,
      List<String> concepts,
      List<String> prerequisites,
      int complexityScore,
      List<ExplanationResponseDto.ExternalLink> externalLinks) {}
}
