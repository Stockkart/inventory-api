package com.inventory.video.mapper;

import com.inventory.video.domain.model.TutorialVideo;
import com.inventory.video.rest.dto.response.TutorialVideoResponse;
import com.inventory.video.utils.VideoRouteUtils;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TutorialVideoMapper {

  @Mapping(
      target = "youtubeVideoId",
      expression = "java(com.inventory.video.utils.YoutubeUrlUtils.resolveVideoId(video))")
  TutorialVideoResponse toResponse(TutorialVideo video);

  List<TutorialVideoResponse> toResponseList(List<TutorialVideo> videos);

  default List<TutorialVideoResponse> toResponseListForRoute(
      List<TutorialVideo> videos, String rawPath) {
    if (videos == null || videos.isEmpty()) {
      return List.of();
    }
    String path = VideoRouteUtils.normalizePath(rawPath);
    return videos.stream()
        .filter(video -> VideoRouteUtils.matchesRoute(video, path))
        .sorted(
            Comparator.comparingInt((TutorialVideo v) -> VideoRouteUtils.routeMatchScore(v, path))
                .reversed()
                .thenComparingInt(TutorialVideo::getSortOrder)
                .thenComparing(TutorialVideo::getTitle, String.CASE_INSENSITIVE_ORDER))
        .map(this::toResponse)
        .toList();
  }
}
