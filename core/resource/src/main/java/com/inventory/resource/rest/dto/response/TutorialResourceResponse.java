package com.inventory.resource.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TutorialResourceResponse {

  private String id;
  private String videoKey;
  private String title;
  private String description;
  private String youtubeUrl;
  private String youtubeVideoId;
  private List<String> routePaths;
  private int sortOrder;
}
