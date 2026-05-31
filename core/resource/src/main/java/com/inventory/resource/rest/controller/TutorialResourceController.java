package com.inventory.resource.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.resource.rest.dto.response.TutorialResourceResponse;
import com.inventory.resource.service.TutorialResourceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class TutorialResourceController {

  private final TutorialResourceService tutorialResourceService;

  /** List all active tutorial resources (authenticated). */
  @GetMapping
  public ResponseEntity<ApiResponse<List<TutorialResourceResponse>>> listResources() {
    return ResponseEntity.ok(ApiResponse.success(tutorialResourceService.listActive()));
  }

  /**
   * Resolve a resource by stable key (e.g. {@code stockkart-overview} for landing demo).
   * Public keys are available without authentication.
   */
  @GetMapping("/key/{videoKey}")
  public ResponseEntity<ApiResponse<TutorialResourceResponse>> getByVideoKey(
      @PathVariable String videoKey) {
    return ResponseEntity.ok(ApiResponse.success(tutorialResourceService.getByVideoKey(videoKey)));
  }

  /** Resources suggested for the current dashboard route (authenticated). */
  @GetMapping("/for-route")
  public ResponseEntity<ApiResponse<List<TutorialResourceResponse>>> listForRoute(
      @RequestParam("path") String path) {
    return ResponseEntity.ok(ApiResponse.success(tutorialResourceService.listForRoute(path)));
  }
}
