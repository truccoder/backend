package com.socialapp.moderation.event;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.socialapp.posts.entity.PostEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationEventPublisher {
  private final ApplicationEventPublisher eventPublisher;

  public void publishForReview(PostEntity post, List<Integer> taggedUserIds) {
    PostModerationEvent event =
        PostModerationEvent.builder()
            .postId(post.getId())
            .authorId(post.getAuthorId())
            .content(post.getContent())
            .imageUrls(post.getImages())
            .taggedUserIds(taggedUserIds)
            .build();

    eventPublisher.publishEvent(event);
    log.debug("Published moderation event for post {}", post.getId());
  }
}
