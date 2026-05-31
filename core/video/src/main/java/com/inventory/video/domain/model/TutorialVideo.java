package com.inventory.video.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * YouTube tutorial linked by a stable {@code videoKey} (e.g. {@code stockkart-overview})
 * and optionally mapped to dashboard routes for contextual guidance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "help_videos")
public class TutorialVideo {

  @Id
  private String id;

  /** Unique slug used by API clients (e.g. {@code stockkart-overview}, {@code demo}). */
  @Indexed(unique = true)
  private String videoKey;

  private String title;

  private String description;

  /** Full YouTube watch URL. */
  private String youtubeUrl;

  /** Extracted id for embeds (e.g. {@code dQw4w9WgXcQ}). */
  private String youtubeVideoId;

  /**
   * Dashboard paths this video applies to (e.g. {@code /dashboard/product-registration}).
   * Empty list means the video is only reachable by {@code videoKey}, not route matching.
   */
  @Builder.Default
  private List<String> routePaths = new ArrayList<>();

  @Builder.Default
  private int sortOrder = 0;

  @Builder.Default
  private boolean active = true;

  /** When true, {@code GET /api/v1/videos/key/{videoKey}} works without auth. */
  @Builder.Default
  private boolean isPublic = false;

  private Instant createdAt;
  private Instant updatedAt;
}
