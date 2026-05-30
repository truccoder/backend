package com.socialapp.posts.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.entity.enums.PostVisibility;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "post_id")
  @OrderBy("id.position ASC")
  private List<PostTagEntity> tags = new ArrayList<>();

  private String googlePlaceId;

  @Enumerated(EnumType.STRING)
  private LocationType locationType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "location_details", columnDefinition = "jsonb")
  private LocationDetails locationDetails;

  private Integer authorId;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
