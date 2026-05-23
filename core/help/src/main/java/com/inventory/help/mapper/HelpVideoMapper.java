package com.inventory.help.mapper;

import com.inventory.help.domain.model.HelpVideo;
import com.inventory.help.rest.dto.response.HelpVideoResponse;
import com.inventory.help.utils.HelpVideoPathUtils;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface HelpVideoMapper {

  @Mapping(
      target = "youtubeVideoId",
      expression = "java(com.inventory.help.utils.YoutubeUrlUtils.resolveVideoId(video))")
  HelpVideoResponse toResponse(HelpVideo video);

  List<HelpVideoResponse> toResponseList(List<HelpVideo> videos);

  default List<HelpVideoResponse> toResponseListForRoute(List<HelpVideo> videos, String rawPath) {
    if (videos == null || videos.isEmpty()) {
      return List.of();
    }
    String path = HelpVideoPathUtils.normalizePath(rawPath);
    return videos.stream()
        .filter(video -> HelpVideoPathUtils.matchesRoute(video, path))
        .sorted(
            Comparator.comparingInt((HelpVideo v) -> HelpVideoPathUtils.routeMatchScore(v, path))
                .reversed()
                .thenComparingInt(HelpVideo::getSortOrder)
                .thenComparing(HelpVideo::getTitle, String.CASE_INSENSITIVE_ORDER))
        .map(this::toResponse)
        .toList();
  }
}
