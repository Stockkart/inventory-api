package com.inventory.video.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.video.domain.model.TutorialVideo;
import com.inventory.video.domain.repository.TutorialVideoRepository;
import com.inventory.video.mapper.TutorialVideoMapper;
import com.inventory.video.rest.dto.response.TutorialVideoResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TutorialVideoService {

  private final TutorialVideoRepository tutorialVideoRepository;
  private final TutorialVideoMapper tutorialVideoMapper;

  public TutorialVideoResponse getByVideoKey(String videoKey) {
    TutorialVideo video =
        tutorialVideoRepository
            .findByVideoKeyAndActiveTrue(videoKey)
            .orElseThrow(
                () -> new ResourceNotFoundException("Tutorial video", "videoKey", videoKey));
    return tutorialVideoMapper.toResponse(video);
  }

  public List<TutorialVideoResponse> listActive() {
    return tutorialVideoMapper.toResponseList(
        tutorialVideoRepository.findByActiveTrueOrderBySortOrderAscTitleAsc());
  }

  public List<TutorialVideoResponse> listForRoute(String rawPath) {
    return tutorialVideoMapper.toResponseListForRoute(
        tutorialVideoRepository.findByActiveTrueOrderBySortOrderAscTitleAsc(), rawPath);
  }

  public boolean isPublicVideo(String videoKey) {
    return tutorialVideoRepository
        .findByVideoKeyAndActiveTrue(videoKey)
        .map(TutorialVideo::isPublic)
        .orElse(false);
  }
}
