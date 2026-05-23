package com.inventory.help.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.help.rest.dto.response.HelpVideoResponse;
import com.inventory.help.service.HelpVideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/help/videos")
@RequiredArgsConstructor
public class HelpVideoController {

  private final HelpVideoService helpVideoService;

  /** List all active help videos (authenticated). */
  @GetMapping
  public ResponseEntity<ApiResponse<List<HelpVideoResponse>>> listVideos() {
    return ResponseEntity.ok(ApiResponse.success(helpVideoService.listActive()));
  }

  /**
   * Resolve a video by stable key (e.g. {@code stockkart-overview} for landing demo).
   * Public keys are available without authentication.
   */
  @GetMapping("/key/{videoKey}")
  public ResponseEntity<ApiResponse<HelpVideoResponse>> getByVideoKey(
      @PathVariable String videoKey) {
    return ResponseEntity.ok(ApiResponse.success(helpVideoService.getByVideoKey(videoKey)));
  }

  /** Videos suggested for the current dashboard route (authenticated). */
  @GetMapping("/for-route")
  public ResponseEntity<ApiResponse<List<HelpVideoResponse>>> listForRoute(
      @RequestParam("path") String path) {
    return ResponseEntity.ok(ApiResponse.success(helpVideoService.listForRoute(path)));
  }
}
