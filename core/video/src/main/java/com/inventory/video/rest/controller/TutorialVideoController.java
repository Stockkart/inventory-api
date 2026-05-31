package com.inventory.video.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.video.rest.dto.response.TutorialVideoResponse;
import com.inventory.video.service.TutorialVideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class TutorialVideoController {

  private final TutorialVideoService tutorialVideoService;

  /** List all active tutorial videos (authenticated). */
  @GetMapping
  public ResponseEntity<ApiResponse<List<TutorialVideoResponse>>> listVideos() {
    return ResponseEntity.ok(ApiResponse.success(tutorialVideoService.listActive()));
  }

  /**
   * Resolve a video by stable key (e.g. {@code stockkart-overview} for landing demo).
   * Public keys are available without authentication.
   */
  @GetMapping("/key/{videoKey}")
  public ResponseEntity<ApiResponse<TutorialVideoResponse>> getByVideoKey(
      @PathVariable String videoKey) {
    return ResponseEntity.ok(ApiResponse.success(tutorialVideoService.getByVideoKey(videoKey)));
  }

  /** Videos suggested for the current dashboard route (authenticated). */
  @GetMapping("/for-route")
  public ResponseEntity<ApiResponse<List<TutorialVideoResponse>>> listForRoute(
      @RequestParam("path") String path) {
    return ResponseEntity.ok(ApiResponse.success(tutorialVideoService.listForRoute(path)));
  }
}
