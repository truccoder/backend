package com.socialapp.posts.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_posts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "posts_seq_gen")
  @SequenceGenerator(name = "posts_seq_gen", sequenceName = "q_posts_id", allocationSize = 1)
  private Integer id;

  private String content;

  @Enumerated(EnumType.STRING)
  private PostVisibility visibility;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "images", columnDefinition = "jsonb")
  private List<String> images;

  // insertable/updatable = false is load-bearing, not documentation. For a unidirectional
  // @OneToMany with a @JoinColumn, Hibernate's default removal plan is
  // "UPDATE t_post_tags SET post_id = NULL" before the DELETE — but post_id is half of this
  // table's primary key, so that statement died on the not-null constraint and every edit of an
  // already-tagged post came back 409. Taking write control of the column away from the
  // association leaves only the DELETE. Nothing is lost on insert: PostTagEntity carries post_id
  // itself, inside its own composite id.
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "post_id", insertable = false, updatable = false)
  @OrderBy("id.position ASC")
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<PostTagEntity> tags = new ArrayList<>();

  private String googlePlaceId;

  @Enumerated(EnumType.STRING)
  private LocationType locationType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "location_details", columnDefinition = "jsonb")
  private LocationDetails locationDetails;

  @Enumerated(EnumType.STRING)
  private PostType postType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "event_details", columnDefinition = "jsonb")
  private EventDetails eventDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "quiz_details", columnDefinition = "jsonb")
  private QuizDetails quizDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "code_snippet_details", columnDefinition = "jsonb")
  private CodeSnippetDetails codeSnippetDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "article_details", columnDefinition = "jsonb")
  private ArticleDetails articleDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "qna_details", columnDefinition = "jsonb")
  private QnaDetails qnaDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "poll_details", columnDefinition = "jsonb")
  private PollDetails pollDetails;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "link_details", columnDefinition = "jsonb")
  private LinkDetails linkDetails;

  @Enumerated(EnumType.STRING)
  private ModerationStatus moderationStatus;

  private Integer authorId;

  @ManyToMany
  @JoinTable(
      name = "t_post_hashtags",
      joinColumns = @JoinColumn(name = "post_id"),
      inverseJoinColumns = @JoinColumn(name = "hashtag_id"))
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private Set<HashtagEntity> hashtags = new HashSet<>();

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
