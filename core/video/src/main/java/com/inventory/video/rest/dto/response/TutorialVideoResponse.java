package com.inventory.video.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TutorialVideoResponse {

  private String id;
  private String videoKey;
  private String title;
  private String description;
  private String youtubeUrl;
  private String youtubeVideoId;
  private List<String> routePaths;
  private int sortOrder;
}
