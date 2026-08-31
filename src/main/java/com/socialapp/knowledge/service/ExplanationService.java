package com.socialapp.knowledge.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.enums.LearningCategory;
import com.socialapp.common.ratelimit.CostlyOperationProperties;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.VaultContextSettingsEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.enums.ExplanationStyle;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.repository.VaultContextSettingsRepository;
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
  private final VaultContextSettingsRepository settingsRepository;
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
    // AN OVERLOAD RATHER THAN A FIFTH ARGUMENT AT EVERY CALL SITE. "No opinion about vault
    // context" is exactly what every existing caller means, and null is what that reads as
    // downstream — so this is not a legacy shim, it is the shorter way to say the common thing.
    return explainPost(userId, postId, feedbackNote, language, null);
  }

  public ExplanationResponseDto explainPost(
      Integer userId,
      Integer postId,
      String feedbackNote,
      String language,
      Boolean useVaultContext) {
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

    // Not post.getContent(): a CODE_SNIPPET keeps its code in a jsonb detail block and an ARTICLE
    // keeps its body in another, so content is routinely blank on exactly the posts worth
    // explaining. explainableText() gathers every block a reader looks at — same fix moderation
    // already made with moderatableText(). Used for both the prompt and originalContent so the
    // card's "original" panel is not blank either.
    String explainable = post.explainableText();
    String vaultContext = loadVaultContext(userId, useVaultContext);
    String prompt = buildPrompt(explainable, profile, feedbackNote, vaultContext, language);
    String geminiResponse = geminiClient.generateContent(prompt);
    GeminiExplanationResult parsed = parseGeminiResponse(geminiResponse);

    return ExplanationResponseDto.builder()
        .postId(postId)
        .originalContent(explainable)
        .explanationContent(parsed.explanation)
        .concepts(parsed.concepts)
        .prerequisites(parsed.prerequisites)
        .complexityScore(parsed.complexityScore)
        // B34: a model that is not grounded produces links that do not resolve — non-ASCII
        // spliced into the path, a fabricated deep path on a real host. Drop the broken ones
        // here so the response, the card, and the saved copy all agree on what is followable.
        .externalLinks(ExternalLinkSanitizer.followable(parsed.externalLinks))
        .category(parsed.category)
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
            // Filtered again on the way in: the generate path already sanitises, but a client can
            // POST here directly, and a broken link is just as unfollowable once it is persisted.
            .externalLinks(ExternalLinkSanitizer.followable(request.getExternalLinks()))
            .complexityScore(request.getComplexityScore())
            .category(request.getCategory())
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

  /**
   * The reader's own notes, summarised for the prompt — or {@code null} when they must not be used.
   *
   * <p>Three gates, in order of how cheap they are to check:
   *
   * <ol>
   *   <li>{@code useVaultContext} — this request's own answer. {@code FALSE} and only {@code FALSE}
   *       turns it off; {@code null} means the caller expressed no preference, which is not the
   *       same as saying no.
   *   <li>A {@code BIDIRECTIONAL} token must exist. This is the permission the notes arrived
   *       under, so it is also the permission they may be read under.
   *   <li>There must be notes.
   * </ol>
   */
  private String loadVaultContext(Integer userId, Boolean useVaultContext) {
    if (Boolean.FALSE.equals(useVaultContext)) {
      return null;
    }

    boolean hasBidirectionalAccess =
        tokenRepository.findByUserId(userId).stream()
            .anyMatch(t -> VaultPermission.BIDIRECTIONAL.equals(t.getVaultPermission()));

    if (!hasBidirectionalAccess) {
      return null;
    }

    /*
     * ORDERED, AND NO LONGER FILTERED BY "HAS TAGS" — two bugs that only showed up together.
     *
     * This used to call findByUserIdWithTags, whose `tags IS NOT NULL` clause SILENTLY DROPPED
     * EVERY UNTAGGED NOTE. A reader who does not tag — which is most people, and the plugin does
     * not require it — synced their whole vault and got no context at all, with nothing anywhere
     * saying why. The tags were never the point either: they are one of three things sent, and a
     * note contributes its filename and links whether or not it has any.
     *
     * The query also had no ORDER BY, so the 50-note slice below was 50 rows in whatever order
     * Postgres felt like returning — which could change after a vacuum. "Which of my notes does
     * the AI see?" had no answer anyone could give. Most-recently-edited is the ordering every
     * reading of this code already assumed.
     */
    List<VaultNoteEntity> notes = vaultNoteRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    notes = applyTagFilter(userId, notes);
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

  /**
   * Narrow the vault to the notes the reader said may be used.
   *
   * <p>The plugin pushes a WHOLE vault — daily logs, meeting notes, everything — and before this
   * filter the only control anyone had was all-or-nothing. Someone who wanted the model to see
   * their backend notes but not their journal had to stop syncing entirely.
   *
   * <p><b>Exclusions are applied after inclusions and always win.</b> A note tagged both
   * {@code #tech} and {@code #private} is dropped. The other order would let a broad include
   * quietly override a deliberate exclusion, and on a privacy control that is the direction of
   * mistake that actually hurts.
   *
   * <p>Comparison is on lower-cased tags because {@code VaultNoteService.normalise} stores the
   * settings that way; note tags come from the plugin unmodified, so the folding happens here.
   */
  private List<VaultNoteEntity> applyTagFilter(Integer userId, List<VaultNoteEntity> notes) {
    VaultContextSettingsEntity settings = settingsRepository.findById(userId).orElse(null);
    if (Objects.isNull(settings)) {
      return notes;
    }

    Set<String> include = Set.copyOf(settings.getIncludeTags());
    Set<String> exclude = Set.copyOf(settings.getExcludeTags());
    if (include.isEmpty() && exclude.isEmpty()) {
      return notes;
    }

    return notes.stream()
        .filter(
            note -> {
              Set<String> tags =
                  note.getTags().stream()
                      .map(tag -> tag.toLowerCase(Locale.ROOT))
                      .collect(Collectors.toSet());

              // An empty include list means "no restriction", not "match nothing" — otherwise
              // configuring only an exclusion would silently blank the whole vault.
              if (!include.isEmpty() && tags.stream().noneMatch(include::contains)) {
                return false;
              }
              return tags.stream().noneMatch(exclude::contains);
            })
        .toList();
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
    sb.append("7. Include 2-5 external links (blog posts, docs, videos) for deeper learning\n");
    // backend-plan B34: the model was indenting sub-bullets by one space. CommonMark needs the
    // indent to reach the width of the parent marker (two columns for "* "), so a one-space
    // sub-item is parsed as a sibling and the whole list flattens to one level in remark-gfm.
    sb.append(
        "8. In \"explanation\", write valid CommonMark. Keep bullet lists to a single level"
            + " wherever you can — prefer a flat list with a **bold lead-in** per item. If you"
            + " genuinely must nest, indent each sub-item by exactly TWO spaces per level.\n");
    // B34: the model invented links — a real host with a fabricated deep path, a title that did
    // not match the page, non-ASCII spliced mid-URL, a different set every regeneration. The
    // service drops the syntactically broken ones (sanitizeExternalLinks), but only the prompt
    // can stop the plausible-looking wrong ones.
    sb.append(
        "9. Every \"externalLinks\" URL must be one you are confident is real and currently"
            + " reachable: an official documentation page, a well-known engineering blog, or a"
            + " conference talk. Use plain ASCII. Do NOT guess deep paths — if you are unsure of"
            + " the exact page, link the site's root, and omit a link rather than invent one. The"
            + " \"title\" must be the actual title of that page.\n\n");

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
          "category": "BACKEND",
          "externalLinks": [
            {"title": "Resource title", "url": "https://...", "reason": "Why this helps"}
          ]
        }

        Where complexityScore is 1-5 (1=beginner friendly, 5=very advanced).
        The "explanation" field should be comprehensive and directly help the reader understand the post without modifying the original meaning.
        externalLinks should be real, reputable URLs (official docs, well-known blogs, conference talks).
        """);

    // Danh sách hằng số sinh từ chính enum, không gõ tay vào text block ở trên: thêm một chủ đề
    // mới mà quên sửa prompt thì model sẽ không bao giờ trả về nó, và lỗi đó im lặng tuyệt đối —
    // mọi bài thuộc chủ đề mới chỉ lặng lẽ rơi vào OTHER.
    String allowedCategories =
        Arrays.stream(LearningCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
    sb.append("\"category\" must be exactly one of: ")
        .append(allowedCategories)
        .append(". Pick the single closest one; use OTHER only when none of the others fit.")
        .append('\n');

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
      LearningCategory category = parseCategory(root.path("category").asText("OTHER"));

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
          explanation, concepts, prerequisites, complexityScore, externalLinks, category);
    } catch (Exception e) {
      log.warn("Failed to parse structured Gemini response, using raw text: {}", e.getMessage());
      return new GeminiExplanationResult(
          response, List.of(), List.of(), 3, List.of(), LearningCategory.OTHER);
    }
  }

  /**
   * Đọc nhãn chủ đề model trả về, hoặc {@code OTHER} nếu nó trả về thứ không có trong enum.
   *
   * <p>Không ném lỗi: một nhãn lạ chỉ làm hỏng cái tab, còn bản giải thích — thứ người dùng chờ
   * và đã trả tiền model để có — thì vẫn dùng được nguyên vẹn. Cùng cách xử lý với {@code
   * TrendingClassificationService.parseCategory}, nơi cũng là một nhãn do model đặt.
   */
  private LearningCategory parseCategory(String category) {
    try {
      return LearningCategory.valueOf(category);
    } catch (IllegalArgumentException e) {
      log.warn("Unknown learning category '{}' from Gemini, keeping OTHER", category);
      return LearningCategory.OTHER;
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
        .category(entity.getCategory())
        .version(entity.getVersion())
        .createdAt(entity.getCreatedAt())
        .build();
  }

  private record GeminiExplanationResult(
      String explanation,
      List<String> concepts,
      List<String> prerequisites,
      int complexityScore,
      List<ExplanationResponseDto.ExternalLink> externalLinks,
      LearningCategory category) {}
}
