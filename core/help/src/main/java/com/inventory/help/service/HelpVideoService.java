package com.inventory.help.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.help.domain.model.HelpVideo;
import com.inventory.help.domain.repository.HelpVideoRepository;
import com.inventory.help.mapper.HelpVideoMapper;
import com.inventory.help.rest.dto.response.HelpVideoResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HelpVideoService {

  private final HelpVideoRepository helpVideoRepository;
  private final HelpVideoMapper helpVideoMapper;

  public HelpVideoResponse getByVideoKey(String videoKey) {
    HelpVideo video =
        helpVideoRepository
            .findByVideoKeyAndActiveTrue(videoKey)
            .orElseThrow(
                () -> new ResourceNotFoundException("Help video", "videoKey", videoKey));
    return helpVideoMapper.toResponse(video);
  }

  public List<HelpVideoResponse> listActive() {
    return helpVideoMapper.toResponseList(
        helpVideoRepository.findByActiveTrueOrderBySortOrderAscTitleAsc());
  }

  public List<HelpVideoResponse> listForRoute(String rawPath) {
    return helpVideoMapper.toResponseListForRoute(
        helpVideoRepository.findByActiveTrueOrderBySortOrderAscTitleAsc(), rawPath);
  }

  public boolean isPublicVideo(String videoKey) {
    return helpVideoRepository
        .findByVideoKeyAndActiveTrue(videoKey)
        .map(HelpVideo::isPublic)
        .orElse(false);
  }
}
