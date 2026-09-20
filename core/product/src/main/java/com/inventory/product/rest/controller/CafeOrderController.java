package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.PunchLine;
import com.inventory.pluginengine.order.RunningOrderView;
import com.inventory.pluginengine.order.VoidResult;
import com.inventory.product.rest.dto.request.CancelCafeOrderRequest;
import com.inventory.product.rest.dto.request.OpenCafeOrderRequest;
import com.inventory.product.rest.dto.request.PunchKotRequest;
import com.inventory.product.rest.dto.request.SettleCafeOrderRequest;
import com.inventory.product.rest.dto.request.VoidKotRequest;
import com.inventory.product.service.vertical.CafeOrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cafe running orders and kitchen tickets.
 *
 * <p>shopId and userId come from request attributes set by the authentication interceptor, never
 * from the body — that is what keeps one shop from addressing another's orders by id.
 */
@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/cafe")
public class CafeOrderController {

  private final CafeOrderService cafeOrderService;

  public CafeOrderController(CafeOrderService cafeOrderService) {
    this.cafeOrderService = cafeOrderService;
  }

  @PostMapping("/orders")
  public ResponseEntity<ApiResponse<RunningOrderView>> openOrder(
      @RequestBody OpenCafeOrderRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeOrderService.openOrder(
                shopId(httpRequest),
                userId(httpRequest),
                request.getOrderType(),
                request.getTableLabel(),
                null)));
  }

  @GetMapping("/orders")
  public ResponseEntity<ApiResponse<List<RunningOrderView>>> listOpenOrders(
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(cafeOrderService.listOpenOrders(shopId(httpRequest))));
  }

  @GetMapping("/orders/{orderId}")
  public ResponseEntity<ApiResponse<RunningOrderView>> getOrder(
      @PathVariable String orderId, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(cafeOrderService.getOrder(shopId(httpRequest), orderId)));
  }

  /** Punch one round. The Idempotency-Key header is required: this is the call that reaches a kitchen. */
  @PostMapping("/orders/{orderId}/kots")
  public ResponseEntity<ApiResponse<List<KotView>>> punch(
      @PathVariable String orderId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PunchKotRequest request,
      HttpServletRequest httpRequest) {
    List<PunchLine> lines =
        request.getLines() == null
            ? List.of()
            : request.getLines().stream()
                .map(
                    line ->
                        PunchLine.builder()
                            .sellableRef(line.getSellableRef())
                            .quantity(line.getQuantity())
                            .note(line.getNote())
                            .build())
                .toList();
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeOrderService.punch(
                shopId(httpRequest), userId(httpRequest), orderId, idempotencyKey, lines)));
  }

  /** Stamp is derived from state. Stays available after a void so the slip can be reprinted for audit. */
  @GetMapping("/kots/{kotId}/document")
  public ResponseEntity<byte[]> kotDocument(
      @PathVariable String kotId, HttpServletRequest httpRequest) {
    return pdf(
        cafeOrderService.kotDocument(shopId(httpRequest), kotId), "kot_" + kotId + ".pdf");
  }

  @PostMapping("/kots/{kotId}/reprint")
  public ResponseEntity<byte[]> reprint(
      @PathVariable String kotId, HttpServletRequest httpRequest) {
    return pdf(
        cafeOrderService.reprint(shopId(httpRequest), kotId), "kot_" + kotId + "_reprint.pdf");
  }

  /** Returns JSON; the cancellation slip is fetched from /kots/{kotId}/document. */
  /** The slip for one past void operation, so a jammed printer does not force a full reprint. */
  @GetMapping("/kots/{kotId}/voids/{voidBatchId}/document")
  public ResponseEntity<byte[]> voidSlip(
      @PathVariable String kotId,
      @PathVariable String voidBatchId,
      HttpServletRequest httpRequest) {
    return pdf(
        cafeOrderService.voidSlip(shopId(httpRequest), kotId, voidBatchId),
        "kot_" + kotId + "_void_" + voidBatchId + ".pdf");
  }

  @PostMapping("/kots/{kotId}/void")
  public ResponseEntity<ApiResponse<VoidResult>> voidLines(
      @PathVariable String kotId,
      @RequestBody VoidKotRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeOrderService.voidLines(
                shopId(httpRequest),
                userId(httpRequest),
                kotId,
                request.getLineIds(),
                request.getReason())));
  }

  @PostMapping("/orders/{orderId}/cancel")
  public ResponseEntity<ApiResponse<RunningOrderView>> cancel(
      @PathVariable String orderId,
      @RequestBody CancelCafeOrderRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeOrderService.cancel(
                shopId(httpRequest), userId(httpRequest), orderId, request.getReason())));
  }

  @PostMapping("/orders/{orderId}/settle")
  public ResponseEntity<ApiResponse<RunningOrderView>> settle(
      @PathVariable String orderId,
      @RequestBody SettleCafeOrderRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeOrderService.settle(
                shopId(httpRequest),
                userId(httpRequest),
                orderId,
                request.getBusinessType(),
                request.getPaymentMethod(),
                httpRequest)));
  }

  private static String shopId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("shopId");
  }

  private static String userId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("userId");
  }

  private ResponseEntity<byte[]> pdf(byte[] bytes, String fileName) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", fileName);
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }
}
