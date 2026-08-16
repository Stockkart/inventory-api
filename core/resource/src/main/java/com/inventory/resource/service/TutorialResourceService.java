package com.inventory.resource.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.resource.domain.model.TutorialResource;
import com.inventory.resource.domain.repository.TutorialResourceRepository;
import com.inventory.resource.mapper.TutorialResourceMapper;
import com.inventory.resource.rest.dto.response.TutorialResourceResponse;
import com.inventory.resource.utils.constants.ResourceMetricsConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TutorialResourceService {

  private final TutorialResourceRepository tutorialResourceRepository;
  private final TutorialResourceMapper tutorialResourceMapper;
  private final MetricsWrapper metrics;

  public TutorialResourceResponse getByVideoKey(String videoKey) {
    TutorialResource resource =
        tutorialResourceRepository
            .findByVideoKeyAndActiveTrue(videoKey)
            .orElseThrow(
                () -> new ResourceNotFoundException("Tutorial resource", "videoKey", videoKey));
    metrics.record(
        ResourceMetricsConstants.TUTORIAL_VIEWS_TOTAL,
        1,
        "module",
        ResourceMetricsConstants.MODULE);
    return tutorialResourceMapper.toResponse(resource);
  }

  public List<TutorialResourceResponse> listActive() {
    return tutorialResourceMapper.toResponseList(
        tutorialResourceRepository.findByActiveTrueOrderBySortOrderAscTitleAsc());
  }

  public List<TutorialResourceResponse> listForRoute(String rawPath) {
    return tutorialResourceMapper.toResponseListForRoute(
        tutorialResourceRepository.findByActiveTrueOrderBySortOrderAscTitleAsc(), rawPath);
  }

  public boolean isPublicResource(String videoKey) {
    return tutorialResourceRepository
        .findByVideoKeyAndActiveTrue(videoKey)
        .map(TutorialResource::isPublic)
        .orElse(false);
  }
}
