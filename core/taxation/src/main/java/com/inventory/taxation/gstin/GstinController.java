package com.inventory.taxation.gstin;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.gst.Gstin;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GSTIN checks for the vendor (and later customer) forms.
 *
 * <ul>
 *   <li>{@code GET /api/v1/taxation/gstin/{gstin}} — offline validation, then the registry, then one
 *       network call for a GSTIN never seen before.
 *   <li>{@code POST /api/v1/taxation/gstin/{gstin}/reverify} — ask the network again.
 * </ul>
 *
 * Both answer 200 whatever the GSTIN: the response says whether it is valid and verified.
 */
@RestController
@RequestMapping("/api/v1/taxation/gstin")
@Latency(module = "taxation")
@RecordRequestRate(module = "taxation")
@RecordStatusCodes(module = "taxation")
public class GstinController {

  private final GstinRegistryService registry;

  public GstinController(GstinRegistryService registry) {
    this.registry = registry;
  }

  @GetMapping("/{gstin}")
  public ResponseEntity<ApiResponse<GstinLookupResponse>> lookup(@PathVariable String gstin, HttpServletRequest http) {
    requireShop(http);
    return ResponseEntity.ok(ApiResponse.success(answer(gstin, registry::lookupRecord)));
  }

  @PostMapping("/{gstin}/reverify")
  public ResponseEntity<ApiResponse<GstinLookupResponse>> reverify(@PathVariable String gstin, HttpServletRequest http) {
    requireShop(http);
    return ResponseEntity.ok(ApiResponse.success(answer(gstin, registry::refreshRecord)));
  }

  private GstinLookupResponse answer(String raw, java.util.function.Function<String, Optional<GstinRecord>> ask) {
    boolean available = registry.provider().isConfigured();
    Optional<String> problem = Gstin.problem(raw);
    if (problem.isPresent()) {
      return GstinLookupResponse.invalid(raw, problem.get(), available);
    }
    Gstin gstin = Gstin.parse(raw).orElseThrow();
    return ask.apply(gstin.value())
        .map(r -> GstinLookupResponse.of(r, available))
        .orElseGet(() -> GstinLookupResponse.unverified(gstin, available));
  }

  private static void requireShop(HttpServletRequest http) {
    String shopId = (String) http.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }
  }
}
