package com.socialapp.posts.service;

import java.beans.FeatureDescriptor;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.event.ModerationEventPublisher;
import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.rule.ModerationRuleEngine;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.HashtagRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import io.jsonwebtoken.lang.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final NewsfeedService newsfeedService;
  private final ModerationRuleEngine moderationRuleEngine;
  private final ModerationEventPublisher moderationEventPublisher;
  private final ModerationProperties moderationProperties;
  private final UserBanService userBanService;
  private final BookService bookService;
  private final HashtagRepository hashtagRepository;
  private final CommentRepository commentRepository;
  private final ReputationEventPublisher reputationEventPublisher;

  private static final int MAX_TAGS = 20;
  private static final Pattern TAG_PLACEHOLDER = Pattern.compile("@\\[(\\d+)]");
  private static final Pattern HASHTAG_PATTERN = Pattern.compile("#(\\w+)");

  @Transactional
  public void createPost(Integer authorId, CreatePostRequestDto request) {
    if (PostType.BOOK.equals(request.getPostType())) {
      throw new ValidationException(
          "Use POST /v1/api/posts/books to create a post with an attached book");
    }
    buildAndSavePost(authorId, request);
  }

  /**
   * Creates a post with an attached book in one call, so callers don't need to create a post
   * first just to obtain a postId to pass into book creation.
   */
  @Transactional
  public void createBookPost(
      Integer authorId,
      CreatePostRequestDto request,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    request.setPostType(PostType.BOOK);
    validateBookDetails(request.getBookDetails());

    PostEntity post = buildAndSavePost(authorId, request);

    log.info(
        "[postId={}, authorId={}] createBookPost: post saved, uploading attached book",
        post.getId(),
        authorId);
    bookService.createBookForPost(
        authorId, post.getId(), request.getBookDetails(), bookFile, coverFile);
  }

  private PostEntity buildAndSavePost(Integer authorId, CreatePostRequestDto request) {
    log.info(
        "[authorId={}] createPost started: postType={}, visibility={}",
        authorId,
        request.getPostType(),
        request.getVisibility());

    checkBanStatus(authorId);
    findUserOrThrow(authorId);
    validateTags(request.getVisibility(), request.getTaggedUserIds(), request.getContent());
    log.info("[authorId={}] createPost: ban check and tag validation passed", authorId);

    if (moderationProperties.isEnabled()) {
      ModerationResult ruleResult = moderationRuleEngine.evaluate(authorId, request.getContent());
      log.info(
          "[authorId={}] createPost: rule engine result status={}, violations={}",
          authorId,
          ruleResult.getStatus(),
          ruleResult.getViolations());
      if (ruleResult.isRejected()) {
        throw new ContentViolationException(ruleResult.getViolations());
      }
    }

    if (PostType.EVENT.equals(request.getPostType())) {
      validateEventDetails(request);
    }
    if (request.getQuizDetails() != null) {
      validateQuizDetails(request.getQuizDetails());
    }

    PostEntity post = new PostEntity();
    BeanUtils.copyProperties(request, post);
    post.setAuthorId(authorId);
    if (request.getPostType() == null) {
      post.setPostType(PostType.REGULAR);
    }

    boolean moderationEnabled = moderationProperties.isEnabled();
    post.setModerationStatus(
        moderationEnabled ? ModerationStatus.PENDING_MODERATION : ModerationStatus.APPROVED);
    postRepository.save(post);

    // Tags reference post.getId() via their composite key, so they can only be built once the
    // post has been saved and assigned an id.
    setTags(post, request.getTaggedUserIds());
    processHashtags(post);
    // Flushed, not just saved: @CreationTimestamp fills createdAt when the INSERT actually runs,
    // and save() only schedules it. Without the flush the fan-out below re-reads this very entity
    // out of the persistence context with createdAt still null and caches a post the feed renders
    // with no date. Only bites when moderation is off — with it on, fan-out happens later, in
    // another transaction, off a row that has already been written.
    postRepository.saveAndFlush(post);

    if (moderationEnabled) {
      log.info(
          "[postId={}, authorId={}] createPost: saved as PENDING_MODERATION, publishing async"
              + " moderation event",
          post.getId(),
          authorId);
      moderationEventPublisher.publishForReview(post, request.getTaggedUserIds());
    } else {
      log.info(
          "[postId={}, authorId={}] createPost: moderation disabled, saved as APPROVED and fanning"
              + " out immediately",
          post.getId(),
          authorId);
      newsfeedService.fanOutPost(post.getId());
    }

    return post;
  }

  @Transactional
  public void updatePost(Integer actorId, Integer postId, UpdatePostRequestDto request) {
    checkBanStatus(actorId);
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    findUserOrThrow(actorId);

    // An omitted field now means "leave alone", which splits tag validation in two.
    //
    // The privacy rule is an invariant of the stored post, so it is judged on the merged result:
    // flipping an already-tagged post to PRIVATE without resending the tags must still fail.
    //
    // The placeholder/duplicate/limit rules describe how a tag list relates to the content it
    // arrived with, so they only apply to a list the caller actually sent. Judging them on tags
    // the caller never mentioned would make editing the caption of a tagged post impossible —
    // the new text has no @[i] placeholders in it, and rejecting that edit is no better than the
    // old behaviour of silently discarding the tags.
    PostVisibility effectiveVisibility =
        Objects.nonNull(request.getVisibility()) ? request.getVisibility() : post.getVisibility();
    List<Integer> effectiveTaggedUserIds =
        Objects.nonNull(request.getTaggedUserIds())
            ? request.getTaggedUserIds()
            : extractTaggedUserIds(post);

    if (PostVisibility.PRIVATE.equals(effectiveVisibility)
        && !CollectionUtils.isEmpty(effectiveTaggedUserIds)) {
      throw new ValidationException("Private posts cannot tag other users");
    }

    if (Objects.nonNull(request.getTaggedUserIds())) {
      String effectiveContent =
          Objects.nonNull(request.getContent()) ? request.getContent() : post.getContent();
      validateTags(effectiveVisibility, request.getTaggedUserIds(), effectiveContent);
    }

    if (request.getQuizDetails() != null) {
      validateQuizDetails(request.getQuizDetails());
    }

    if (moderationProperties.isEnabled()) {
      ModerationResult ruleResult = moderationRuleEngine.evaluate(actorId, request.getContent());
      if (ruleResult.isRejected()) {
        throw new ContentViolationException(ruleResult.getViolations());
      }
    }

    BeanUtils.copyProperties(request, post, nullPropertyNames(request));

    // Rebuilding the tag list is only correct when the caller actually sent one. Unconditionally
    // clearing it made "edit the caption" silently un-tag everybody.
    if (Objects.nonNull(request.getTaggedUserIds())) {
      post.getTags().clear();
      postRepository.flush();
      setTags(post, request.getTaggedUserIds());
    }
    processHashtags(post);

    if (moderationProperties.isEnabled()) {
      post.setModerationStatus(ModerationStatus.PENDING_MODERATION);
      postRepository.save(post);
      newsfeedService.removePost(postId, actorId, extractTaggedUserIds(post));
      moderationEventPublisher.publishForReview(post, request.getTaggedUserIds());
    } else {
      postRepository.save(post);
      newsfeedService.fanOutPost(post.getId());
    }
  }

  /**
   * Marks a comment as the accepted answer on a QNA post — author-only, one accept per post.
   * Awards reputation to the answer's author (skipped if the post author answered their own
   * question, to avoid self-crediting).
   */
  @Transactional
  public void acceptAnswer(Integer actorId, Integer postId, Integer commentId) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);

    if (post.getPostType() != PostType.QNA || post.getQnaDetails() == null) {
      throw new ValidationException("Only QNA posts can have an accepted answer");
    }
    QnaDetails qnaDetails = post.getQnaDetails();
    if (qnaDetails.getAcceptedAnswerId() != null) {
      throw new ValidationException("An answer has already been accepted for this post");
    }

    CommentEntity comment =
        commentRepository
            .findById(commentId)
            .orElseThrow(() -> new NotFoundException("Comment not found with ID: " + commentId));
    if (!comment.getPostId().equals(postId)) {
      throw new ValidationException("Comment does not belong to this post");
    }

    qnaDetails.setAcceptedAnswerId(commentId);
    // isResolved is written nowhere else — posts are created with false and no other path flips
    // it — so without this line every answered question stayed labelled "unanswered" forever.
    qnaDetails.setIsResolved(true);
    postRepository.save(post);

    // The feed serves qnaDetails straight out of Redis and never re-reads Postgres, so fixing the
    // row alone would leave readers looking at the stale "unanswered" copy until the next fan-out.
    newsfeedService.updateCachedQnaDetails(postId, qnaDetails);

    if (!comment.getAuthorId().equals(actorId)) {
      reputationEventPublisher.award(
          comment.getAuthorId(), RepSourceType.ACCEPTED_ANSWER, commentId.toString());
    }
  }

  /**
   * Takes back the accepted answer on a QNA post — author-only, the counterpart of {@link
   * #acceptAnswer}. Without it the first pick was permanent: {@code acceptAnswer} refuses to run a
   * second time, so a misclick left the wrong comment crowned forever.
   *
   * <p>Reputation awarded for the pick is revoked with the same {@code sourceId} it was granted
   * under, otherwise accepting and un-accepting in a loop would mint points. The post returns to
   * unresolved, which is the honest state: no answer is accepted any more.
   */
  @Transactional
  public void unacceptAnswer(Integer actorId, Integer postId) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);

    if (post.getPostType() != PostType.QNA || post.getQnaDetails() == null) {
      throw new ValidationException("Only QNA posts can have an accepted answer");
    }
    QnaDetails qnaDetails = post.getQnaDetails();
    Integer acceptedAnswerId = qnaDetails.getAcceptedAnswerId();
    if (acceptedAnswerId == null) {
      throw new ValidationException("No answer has been accepted for this post");
    }

    qnaDetails.setAcceptedAnswerId(null);
    qnaDetails.setIsResolved(false);
    postRepository.save(post);
    newsfeedService.updateCachedQnaDetails(postId, qnaDetails);

    // Mirrors the award in acceptAnswer, including the "not for your own answer" rule — revoking
    // points that were never granted would push the answerer's score below what they earned.
    commentRepository
        .findById(acceptedAnswerId)
        .filter(comment -> !comment.getAuthorId().equals(actorId))
        .ifPresent(
            comment ->
                reputationEventPublisher.revoke(
                    comment.getAuthorId(),
                    RepSourceType.ACCEPTED_ANSWER,
                    acceptedAnswerId.toString()));
  }

  @Transactional
  public void deletePost(Integer actorId, Integer postId) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);

    List<Integer> taggedUserIds = extractTaggedUserIds(post);
    postRepository.delete(post);

    try {
      newsfeedService.removePost(postId, actorId, taggedUserIds);
    } catch (Exception e) {
      log.warn("Failed to remove post {} from feeds: {}", postId, e.getMessage());
    }
  }

  /**
   * Names of every property that is null on {@code source}, for {@code BeanUtils.copyProperties}
   * to skip.
   *
   * <p>Without this, an update wrote null over any column the request did not mention, so a
   * client that edited only the caption erased the post's quiz, poll, images and tags. Treating
   * an absent field as "leave alone" is the only reading that makes a partial update safe.
   *
   * <p>The trade-off is deliberate: a field can no longer be cleared by sending {@code null}.
   * Lists still accept {@code []} to mean "empty this", but a detail block (quiz, poll, article…)
   * cannot be removed through this endpoint at all. Losing the ability to delete a block is worth
   * far less than losing a user's data to a routine edit.
   */
  private static String[] nullPropertyNames(Object source) {
    BeanWrapper wrapper = new BeanWrapperImpl(source);
    return Stream.of(wrapper.getPropertyDescriptors())
        .map(FeatureDescriptor::getName)
        .filter(name -> Objects.isNull(wrapper.getPropertyValue(name)))
        .toArray(String[]::new);
  }

  private void validateTags(
      PostVisibility visibility, List<Integer> taggedUserIds, String content) {
    if (PostVisibility.PRIVATE.equals(visibility) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Private posts cannot tag other users");
    }

    if (!Strings.hasText(content) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Content is required if want to tag users");
    }

    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }

    if (taggedUserIds.size() > MAX_TAGS) {
      throw new ValidationException("Cannot tag more than " + MAX_TAGS + " users");
    }

    Set<Integer> uniqueUsers = new HashSet<>(taggedUserIds);
    if (uniqueUsers.size() != taggedUserIds.size()) {
      throw new ValidationException("Duplicate users in tag list");
    }

    Set<Integer> placeholderIndices = new HashSet<>();
    Matcher matcher = TAG_PLACEHOLDER.matcher(content);
    while (matcher.find()) {
      placeholderIndices.add(Integer.parseInt(matcher.group(1)));
    }

    for (int i = 0; i < taggedUserIds.size(); i++) {
      if (!placeholderIndices.contains(i)) {
        throw new ValidationException(
            "Missing placeholder @[" + i + "] in content for tagged user at position " + i);
      }
    }
  }

  private void setTags(PostEntity post, List<Integer> taggedUserIds) {
    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }
    for (int i = 0; i < taggedUserIds.size(); i++) {
      PostTagEntity tag = new PostTagEntity(new PostTagId(post.getId(), i), taggedUserIds.get(i));
      post.getTags().add(tag);
    }
  }

  private List<Integer> extractTaggedUserIds(PostEntity post) {
    if (CollectionUtils.isEmpty(post.getTags())) {
      return List.of();
    }
    return post.getTags().stream()
        .sorted(Comparator.comparing(a -> a.getId().getPosition()))
        .map(PostTagEntity::getTaggedUserId)
        .toList();
  }

  private PostEntity findPostOrThrow(Integer postId) {
    return postRepository
        .findById(postId)
        .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));
  }

  private UserEntity findUserOrThrow(Integer userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId));
  }

  private void verifyAuthor(Integer actorId, PostEntity post) {
    if (!post.getAuthorId().equals(actorId)) {
      throw new ForbiddenException("Only the author can modify this post");
    }
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }

  private void validateEventDetails(CreatePostRequestDto request) {
    if (request.getEventDetails() == null) {
      throw new ValidationException("Event details are required for event posts");
    }
    if (request.getEventDetails().getEventTitle() == null
        || request.getEventDetails().getEventTitle().isBlank()) {
      throw new ValidationException("Event title is required");
    }
    if (request.getEventDetails().getStartTime() == null) {
      throw new ValidationException("Event start time is required");
    }
    if (request.getEventDetails().getEndTime() == null) {
      throw new ValidationException("Event end time is required");
    }
    if (request.getEventDetails().getEndTime().isBefore(request.getEventDetails().getStartTime())) {
      throw new ValidationException("Event end time must be after start time");
    }
  }

  private void validateBookDetails(CreateBookRequestDto bookDetails) {
    if (bookDetails == null) {
      throw new ValidationException("Book details are required for book posts");
    }
    if (bookDetails.getTitle() == null || bookDetails.getTitle().isBlank()) {
      throw new ValidationException("Book title is required");
    }
  }

  private void validateQuizDetails(QuizDetails quizDetails) {
    if (!Strings.hasText(quizDetails.getTitle())) {
      throw new ValidationException("Quiz title is required");
    }
    if (CollectionUtils.isEmpty(quizDetails.getQuestions())) {
      throw new ValidationException("Quiz must have at least one question");
    }
    for (int i = 0; i < quizDetails.getQuestions().size(); i++) {
      QuizQuestion q = quizDetails.getQuestions().get(i);
      if (!Strings.hasText(q.getQuestion())) {
        throw new ValidationException("Question text is required at index " + i);
      }
      if (CollectionUtils.isEmpty(q.getOptions()) || q.getOptions().size() < 2) {
        throw new ValidationException("Question must have at least 2 options at index " + i);
      }
      if (q.getCorrectOptionIndex() == null
          || q.getCorrectOptionIndex() < 0
          || q.getCorrectOptionIndex() >= q.getOptions().size()) {
        throw new ValidationException("Invalid correctOptionIndex at index " + i);
      }
    }
  }

  private void processHashtags(PostEntity post) {
    if (post.getHashtags() == null) {
      post.setHashtags(new HashSet<>());
    }

    // Decrease usage count for old hashtags if updating
    if (!post.getHashtags().isEmpty()) {
      post.getHashtags()
          .forEach(
              h -> {
                if (h.getUsageCount() != null && h.getUsageCount() > 0) {
                  h.setUsageCount(h.getUsageCount() - 1);
                }
              });
      hashtagRepository.saveAll(post.getHashtags());
    }

    Set<HashtagEntity> newHashtags = new HashSet<>();
    if (Strings.hasText(post.getContent())) {
      Matcher matcher = HASHTAG_PATTERN.matcher(post.getContent());
      Set<String> tagNames = new HashSet<>();
      while (matcher.find()) {
        tagNames.add(matcher.group(1).toLowerCase());
      }

      if (!tagNames.isEmpty()) {
        List<HashtagEntity> existingTags = hashtagRepository.findByNameIn(tagNames);
        Set<String> existingNames =
            existingTags.stream().map(HashtagEntity::getName).collect(Collectors.toSet());

        for (String name : tagNames) {
          if (!existingNames.contains(name)) {
            HashtagEntity newTag = new HashtagEntity();
            newTag.setName(name);
            newTag.setUsageCount(0);
            existingTags.add(newTag);
          }
        }

        // Increase usage count for tags that will be linked
        for (HashtagEntity tag : existingTags) {
          tag.setUsageCount((tag.getUsageCount() == null ? 0 : tag.getUsageCount()) + 1);
        }

        List<HashtagEntity> savedTags = hashtagRepository.saveAll(existingTags);
        newHashtags.addAll(savedTags);
      }
    }

    post.getHashtags().clear();
    post.getHashtags().addAll(newHashtags);
  }
}
