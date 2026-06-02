package com.socialapp.knowledge.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExplanationService {
  private final GeminiClient geminiClient;
  private final ExplanationRepository explanationRepository;
  private final UserProfessionalProfileRepository profileRepository;
  private final VaultNoteRepository vaultNoteRepository;
  private final PersonalAccessTokenRepository tokenRepository;
  private final PostRepository postRepository;
  private final ObjectMapper objectMapper;

  /**
   * Generate explanation without saving. Returns result for user to decide whether to save.
   * Throws 428 if professional profile is not set up.
   */
  public ExplanationResponseDto explainPost(Integer userId, Integer postId, String feedbackNote) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found: " + postId));

    UserProfessionalProfileEntity profile = profileRepository.findById(userId).orElse(null);
    if (Objects.isNull(profile)) {
      throw new ResponseStatusException(
          HttpStatus.PRECONDITION_REQUIRED,
          "Professional profile required. Please set up your profile first.");
    }

    String vaultContext = loadVaultContext(userId);
    String prompt = buildPrompt(post.getContent(), profile, feedbackNote, vaultContext);
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
      String vaultContext) {
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
    sb.append("6. Respond in the same language as the original post\n");
    sb.append("7. Include 2-5 external links (blog posts, docs, videos) for deeper learning\n\n");

    sb.append("=== READER PROFILE ===\n");
    sb.append("- Job title: ").append(profile.getJobTitle()).append("\n");
    sb.append("- Seniority: ").append(profile.getSeniorityLevel()).append("\n");
    sb.append("- Years of experience: ").append(profile.getYearsOfExperience()).append("\n");
    sb.append("- Known tech stack: ").append(profile.getKnownTechStack()).append("\n");
    sb.append("- Work domains: ");
    if (profile.getWorkHistory() != null) {
      profile.getWorkHistory().forEach(w -> sb.append(w.getDomain()).append(", "));
    }
    sb.append("\n");
    sb.append("- Interested in: ").append(profile.getInterestedDomains()).append("\n\n");

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
