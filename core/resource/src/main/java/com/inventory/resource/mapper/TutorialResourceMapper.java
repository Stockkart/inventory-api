package com.inventory.resource.mapper;

import com.inventory.resource.domain.model.TutorialResource;
import com.inventory.resource.rest.dto.response.TutorialResourceResponse;
import com.inventory.resource.utils.ResourceRouteUtils;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TutorialResourceMapper {

  @Mapping(
      target = "youtubeVideoId",
      expression =
          "java(com.inventory.resource.utils.YoutubeUrlUtils.resolveVideoId(resource))")
  TutorialResourceResponse toResponse(TutorialResource resource);

  List<TutorialResourceResponse> toResponseList(List<TutorialResource> resources);

  default List<TutorialResourceResponse> toResponseListForRoute(
      List<TutorialResource> resources, String rawPath) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    String path = ResourceRouteUtils.normalizePath(rawPath);
    return resources.stream()
        .filter(resource -> ResourceRouteUtils.matchesRoute(resource, path))
        .sorted(
            Comparator.comparingInt(
                    (TutorialResource r) -> ResourceRouteUtils.routeMatchScore(r, path))
                .reversed()
                .thenComparingInt(TutorialResource::getSortOrder)
                .thenComparing(TutorialResource::getTitle, String.CASE_INSENSITIVE_ORDER))
        .map(this::toResponse)
        .toList();
  }
}
