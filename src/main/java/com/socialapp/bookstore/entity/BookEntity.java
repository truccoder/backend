package com.socialapp.bookstore.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.bookstore.entity.enums.FileFormat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_books")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "books_seq_gen")
  @SequenceGenerator(name = "books_seq_gen", sequenceName = "q_books_id", allocationSize = 1)
  private Integer id;

  private Integer authorId;

  private Integer postId;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  private String fileKey;

  private String coverImageUrl;

  @Enumerated(EnumType.STRING)
  private FileFormat fileFormat;

  private Long fileSizeBytes;

  private Integer totalPages;

  private Integer previewPages;

  @Builder.Default private Long price = 0L;

  @Builder.Default private String currency = "VND";

  @Builder.Default private Boolean isFree = true;

  @Builder.Default private Integer downloadCount = 0;

  @Builder.Default private Double avgRating = 0.0;

  @Builder.Default private Integer reviewCount = 0;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
