package com.socialapp.posts.service;

import java.beans.FeatureDescriptor;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.EventDetails;
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
  private final NotificationService notificationService;

  private static final int MAX_TAGS = 20;
  private static final Pattern TAG_PLACEHOLDER = Pattern.compile("@\\[(\\d+)]");
  private static final Pattern HASHTAG_PATTERN = Pattern.compile("#(\\w+)");

  /**
   * @return the saved post — its generated id and moderation status are what the composer needs to
   *     navigate to what it just published (B39). The entity is returned rather than {@code void}
   *     purely so the controller can read those two fields.
   */
  @Transactional
  public PostEntity createPost(Integer authorId, CreatePostRequestDto request) {
    if (PostType.BOOK.equals(request.getPostType())) {
      throw new ValidationException(
          "Use POST /v1/api/posts/books to create a post with an attached book");
    }
    return buildAndSavePost(authorId, request);
  }

  /**
   * Creates a post with an attached book in one call, so callers don't need to create a post
   * first just to obtain a postId to pass into book creation.
   *
   * @return the saved post, for the same reason as {@link #createPost}.
   */
  @Transactional
  public PostEntity createBookPost(
      Integer authorId,
      CreatePostRequestDto request,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    request.setPostType(PostType.BOOK);
    validateBookDetails(request.getBookDetails());

    // Fan-out is held back here on purpose. NewsfeedService reads t_books by postId to build the
    // book block of the cached feed entry, and that row does not exist until createBookForPost
    // below has run — fanning out from buildAndSavePost would cache the post with book: null for
    // seven days, and only a later edit (which fans out again) would repair it. Same shape as the
    // createdAt bug, but saveAndFlush does not fix it: the row is genuinely not written yet.
    PostEntity post = buildAndSavePost(authorId, request, true);

    log.info(
        "[postId={}, authorId={}] createBookPost: post saved, uploading attached book",
        post.getId(),
        authorId);
    bookService.createBookForPost(
        authorId, post.getId(), request.getBookDetails(), bookFile, coverFile);

    // Only the moderation-off path deferred anything: with moderation on, nothing was fanned out
    // at all and the post reaches the feed later, through ModerationEventListener, by which time
    // the book row is long committed.
    if (!moderationProperties.isEnabled()) {
      log.info(
          "[postId={}, authorId={}] createBookPost: book saved, running deferred fan-out",
          post.getId(),
          authorId);
      newsfeedService.fanOutPost(post.getId());
    }

    return post;
  }

  private PostEntity buildAndSavePost(Integer authorId, CreatePostRequestDto request) {
    return buildAndSavePost(authorId, request, false);
  }

  /**
   * @param deferFanOut when true, skips the immediate fan-out of the moderation-off path so the
   *     caller can publish the post once the rows it depends on exist. Has no effect when
   *     moderation is enabled, since that path does not fan out here anyway.
   */
  private PostEntity buildAndSavePost(
      Integer authorId, CreatePostRequestDto request, boolean deferFanOut) {
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
      // The whole post, not just request.getContent(). See PostEntity#moderatableText: seven detail
      // blocks carry free text that nothing used to read, so an ARTICLE with an empty body and the
      // real content in articleDetails walked straight past the filter.
      ModerationResult ruleResult =
          moderationRuleEngine.evaluate(authorId, moderatableTextOf(request));
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

    publishSavedPost(post, request, moderationEnabled, deferFanOut);

    return post;
  }

  /** Hands a freshly saved post to whichever publication path applies. */
  private void publishSavedPost(
      PostEntity post,
      CreatePostRequestDto request,
      boolean moderationEnabled,
      boolean deferFanOut) {
    if (moderationEnabled) {
      log.info(
          "[postId={}, authorId={}] createPost: saved as PENDING_MODERATION, publishing async"
              + " moderation event",
          post.getId(),
          post.getAuthorId());
      moderationEventPublisher.publishForReview(post, request.getTaggedUserIds());
      return;
    }

    if (deferFanOut) {
      log.info(
          "[postId={}, authorId={}] createPost: moderation disabled, saved as APPROVED, fan-out"
              + " deferred to the caller",
          post.getId(),
          post.getAuthorId());
      return;
    }

    log.info(
        "[postId={}, authorId={}] createPost: moderation disabled, saved as APPROVED and fanning"
            + " out immediately",
        post.getId(),
        post.getAuthorId());
    newsfeedService.fanOutPost(post.getId());
  }

  @Transactional
  public void updatePost(Integer actorId, Integer postId, UpdatePostRequestDto request) {
    checkBanStatus(actorId);
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    findUserOrThrow(actorId);

    validateUpdateTags(request, post);
    if (request.getQuizDetails() != null) {
      validateQuizDetails(request.getQuizDetails());
    }

    mergeAndModerateUpdate(actorId, request, post);
    applyUpdatedTags(request, post);
    processHashtags(post);
    saveUpdatedPost(actorId, postId, request, post);
  }

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
  private void validateUpdateTags(UpdatePostRequestDto request, PostEntity post) {
    PostVisibility effectiveVisibility =
        Objects.nonNull(request.getVisibility()) ? request.getVisibility() : post.getVisibility();
    List<Integer> effectiveTaggedUserIds =
        Objects.nonNull(request.getTaggedUserIds())
            ? request.getTaggedUserIds()
            : extractTaggedUserIds(post);

    rejectPrivateWithTags(effectiveVisibility, effectiveTaggedUserIds);

    if (Objects.nonNull(request.getTaggedUserIds())) {
      String effectiveContent =
          Objects.nonNull(request.getContent()) ? request.getContent() : post.getContent();
      validateTags(effectiveVisibility, request.getTaggedUserIds(), effectiveContent);
    }
  }

  // Merge first, then moderate the result. An edit that leaves a field out means "keep it", so
  // moderating the request alone re-checked only what the caller happened to resend — and when
  // `content` was omitted that was nothing at all. The merge is the same one the tag rules above
  // compute by hand; running it here means the engine judges the post as it will actually be
  // stored, across every detail block (see PostEntity#moderatableText).
  //
  // Safe to do before the check because a rejection throws, and the surrounding @Transactional
  // rolls the entity back with it.
  private void mergeAndModerateUpdate(
      Integer actorId, UpdatePostRequestDto request, PostEntity post) {
    BeanUtils.copyProperties(request, post, nullPropertyNames(request));
    // Marks this as an author-driven edit, distinct from updatedAt — see PostEntity#editedAt.
    // Set unconditionally: even an edit moderation later rejects already changed the content the
    // author intended to publish.
    post.setEditedAt(OffsetDateTime.now());

    if (moderationProperties.isEnabled()) {
      ModerationResult ruleResult = moderationRuleEngine.evaluate(actorId, post.moderatableText());
      if (ruleResult.isRejected()) {
        throw new ContentViolationException(ruleResult.getViolations());
      }
    }
  }

  // Rebuilding the tag list is only correct when the caller actually sent one. Unconditionally
  // clearing it made "edit the caption" silently un-tag everybody.
  private void applyUpdatedTags(UpdatePostRequestDto request, PostEntity post) {
    if (Objects.nonNull(request.getTaggedUserIds())) {
      post.getTags().clear();
      postRepository.flush();
      setTags(post, request.getTaggedUserIds());
    }
  }

  private void saveUpdatedPost(
      Integer actorId, Integer postId, UpdatePostRequestDto request, PostEntity post) {
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

    revokeReputationForDeletedPost(post);

    // Before the post, not after: the book is what the post is for, and BookService refuses to
    // delete one that has been sold. Running it first means such a delete fails with the book's
    // own message and the post survives, instead of the post vanishing and the row surviving with
    // post_id nulled. Guarded by the type check so ordinary posts don't pay for an extra query.
    if (PostType.BOOK.equals(post.getPostType())) {
      bookService.deleteBooksForPost(postId);
    }

    postRepository.delete(post);

    // B42: a like/comment/mention notification about this post (or a comment underneath it)
    // otherwise outlives the post and opens onto a 404 — the only way anyone found out was
    // clicking a dead link. Same transaction as the delete: either both happen or neither does.
    notificationService.deleteForPost(postId);

    try {
      newsfeedService.removePost(postId, actorId, taggedUserIds);
    } catch (Exception e) {
      log.warn("Failed to remove post {} from feeds: {}", postId, e.getMessage());
    }
  }

  /**
   * Clears the reputation a post earned before the post itself is deleted.
   *
   * <p>{@code t_reputation_events} has no foreign key to {@code t_posts} — {@code source_id} is
   * text — so a deleted post's points would otherwise stay on the ledger forever, and the nightly
   * reconcile re-sums that same ledger and never notices. Two kinds accrue against a post:
   *
   * <ul>
   *   <li>{@code REACTION_RECEIVED}: one row per reactor for the author, keyed {@code
   *       "{postId}:{reactorId}"} — bulk-revoked by prefix;
   *   <li>{@code ACCEPTED_ANSWER}: on a resolved QNA post, one row for whoever wrote the accepted
   *       reply, keyed by that comment's id. The comment author id is read now, before the
   *       cascade delete takes the comment with the post.
   * </ul>
   *
   * Comment reactions award no reputation ({@code CommentReactionService}), so the comments that
   * vanish with the post carry nothing else to settle.
   */
  private void revokeReputationForDeletedPost(PostEntity post) {
    reputationEventPublisher.revokeByPrefix(
        post.getAuthorId(), RepSourceType.REACTION_RECEIVED, post.getId() + ":");

    if (PostType.QNA.equals(post.getPostType())
        && post.getQnaDetails() != null
        && post.getQnaDetails().getAcceptedAnswerId() != null) {
      Integer answerId = post.getQnaDetails().getAcceptedAnswerId();
      commentRepository
          .findById(answerId)
          .ifPresent(
              answer ->
                  reputationEventPublisher.revoke(
                      answer.getAuthorId(), RepSourceType.ACCEPTED_ANSWER, answerId.toString()));
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

  /**
   * The text a not-yet-saved post would present to moderation.
   *
   * <p>{@code PostEntity#moderatableText} is the single definition of "everything a reader will
   * see", and the create path has no entity yet when the rule engine runs — it deliberately runs
   * before anything is written. Rather than keep a second list of fields in sync with the entity's,
   * this projects the request onto a throwaway entity and asks it. One list, two callers.
   */
  private String moderatableTextOf(CreatePostRequestDto request) {
    PostEntity projection = new PostEntity();
    BeanUtils.copyProperties(request, projection);
    return projection.moderatableText();
  }

  private void validateTags(
      PostVisibility visibility, List<Integer> taggedUserIds, String content) {
    rejectPrivateWithTags(visibility, taggedUserIds);

    if (!Strings.hasText(content) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Content is required if want to tag users");
    }

    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }

    validateTagListShape(taggedUserIds);
    validateTagPlaceholders(taggedUserIds, content);
  }

  private void rejectPrivateWithTags(PostVisibility visibility, List<Integer> taggedUserIds) {
    if (PostVisibility.PRIVATE.equals(visibility) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Private posts cannot tag other users");
    }
  }

  private void validateTagListShape(List<Integer> taggedUserIds) {
    if (taggedUserIds.size() > MAX_TAGS) {
      throw new ValidationException("Cannot tag more than " + MAX_TAGS + " users");
    }

    Set<Integer> uniqueUsers = new HashSet<>(taggedUserIds);
    if (uniqueUsers.size() != taggedUserIds.size()) {
      throw new ValidationException("Duplicate users in tag list");
    }
  }

  private void validateTagPlaceholders(List<Integer> taggedUserIds, String content) {
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
    EventDetails eventDetails = request.getEventDetails();
    if (eventDetails == null) {
      throw new ValidationException("Event details are required for event posts");
    }
    validateEventRequiredFields(eventDetails);
    validateEventTimeRange(eventDetails);
  }

  private void validateEventRequiredFields(EventDetails eventDetails) {
    if (eventDetails.getEventTitle() == null || eventDetails.getEventTitle().isBlank()) {
      throw new ValidationException("Event title is required");
    }
    if (eventDetails.getStartTime() == null) {
      throw new ValidationException("Event start time is required");
    }
    if (eventDetails.getEndTime() == null) {
      throw new ValidationException("Event end time is required");
    }
  }

  private void validateEventTimeRange(EventDetails eventDetails) {
    if (eventDetails.getEndTime().isBefore(eventDetails.getStartTime())) {
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
    List<QuizQuestion> questions = quizDetails.getQuestions();
    for (int i = 0; i < questions.size(); i++) {
      validateQuizQuestion(questions.get(i), i);
    }
  }

  private void validateQuizQuestion(QuizQuestion question, int index) {
    if (!Strings.hasText(question.getQuestion())) {
      throw new ValidationException("Question text is required at index " + index);
    }
    if (CollectionUtils.isEmpty(question.getOptions()) || question.getOptions().size() < 2) {
      throw new ValidationException("Question must have at least 2 options at index " + index);
    }
    validateCorrectOptionIndex(question, index);
  }

  private void validateCorrectOptionIndex(QuizQuestion question, int index) {
    Integer correctOptionIndex = question.getCorrectOptionIndex();
    if (correctOptionIndex == null
        || correctOptionIndex < 0
        || correctOptionIndex >= question.getOptions().size()) {
      throw new ValidationException("Invalid correctOptionIndex at index " + index);
    }
  }

  private void processHashtags(PostEntity post) {
    if (post.getHashtags() == null) {
      post.setHashtags(new HashSet<>());
    }

    // Decrease usage count for old hashtags if updating. In the database, like the increment
    // below: read-minus-one-write in Java lost a concurrent decrement on a shared tag, and a
    // counter nothing recomputes from the join table never recovers from that.
    if (!post.getHashtags().isEmpty()) {
      hashtagRepository.decrementUsage(
          post.getHashtags().stream().map(HashtagEntity::getName).toArray(String[]::new));
    }

    Set<HashtagEntity> newHashtags = new HashSet<>();
    if (Strings.hasText(post.getContent())) {
      Matcher matcher = HASHTAG_PATTERN.matcher(post.getContent());
      Set<String> tagNames = new HashSet<>();
      while (matcher.find()) {
        tagNames.add(matcher.group(1).toLowerCase(Locale.ROOT));
      }

      if (!tagNames.isEmpty()) {
        // Create-then-increment, both in the database. The previous version selected the existing
        // tags, built the missing ones in memory, incremented every counter in Java and saved the
        // lot — which lost a concurrent increment on a shared tag, and raced two posters straight
        // into the UNIQUE constraint on t_hashtags.name. Postgres arbitrates both now.
        String[] names = tagNames.toArray(String[]::new);
        hashtagRepository.createMissing(names);
        hashtagRepository.incrementUsage(names);
        newHashtags.addAll(hashtagRepository.findByNameIn(tagNames));
      }
    }

    post.getHashtags().clear();
    post.getHashtags().addAll(newHashtags);
  }
}
