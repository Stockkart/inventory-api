package com.inventory.tax.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.tax.rest.dto.GstReturnDto;
import com.inventory.tax.rest.dto.GstReturnListResponse;
import com.inventory.tax.rest.dto.GstSummaryResponse;
import com.inventory.tax.rest.dto.Gstr1Response;
import com.inventory.tax.rest.dto.Gstr3bResponse;
import com.inventory.tax.service.GstReturnService;
import com.inventory.tax.validation.GstValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for GST return operations.
 */
@RestController
@RequestMapping("/api/v1/gst")
@RequiredArgsConstructor
@Slf4j
public class GstController {
    
    private final GstReturnService gstReturnService;
    private final GstValidator gstValidator;
    
    /**
     * Get GST summary for a period.
     * 
     * @param period Period in YYYY-MM format (e.g., 2026-01)
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<GstSummaryResponse>> getSummary(
            @RequestParam String period,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        gstValidator.validateSummaryRequest(shopId, period);
        
        log.info("Getting GST summary for shop {} period {}", shopId, period);
        
        GstSummaryResponse response = gstReturnService.getSummary(shopId, period);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Generate GSTR-1 report (Outward Supplies).
     * 
     * @param period Period in YYYY-MM format (e.g., 2026-01)
     */
    @GetMapping("/gstr1")
    public ResponseEntity<ApiResponse<Gstr1Response>> getGstr1(
            @RequestParam String period,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        gstValidator.validateSummaryRequest(shopId, period);
        
        log.info("Getting GSTR-1 for shop {} period {}", shopId, period);
        
        Gstr1Response response = gstReturnService.getGstr1(shopId, period);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Generate GSTR-3B report (Summary Return).
     * 
     * @param period Period in YYYY-MM format (e.g., 2026-01)
     */
    @GetMapping("/gstr3b")
    public ResponseEntity<ApiResponse<Gstr3bResponse>> getGstr3b(
            @RequestParam String period,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        gstValidator.validateSummaryRequest(shopId, period);
        
        log.info("Getting GSTR-3B for shop {} period {}", shopId, period);
        
        Gstr3bResponse response = gstReturnService.getGstr3b(shopId, period);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Save/Generate a GST return record.
     * 
     * @param period Period in YYYY-MM format
     * @param returnType Return type (GSTR1 or GSTR3B)
     */
    @PostMapping("/returns")
    public ResponseEntity<ApiResponse<GstReturnDto>> generateReturn(
            @RequestParam String period,
            @RequestParam String returnType,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        String userId = getUserId(httpRequest);
        
        gstValidator.validateSummaryRequest(shopId, period);
        gstValidator.validateReturnType(returnType);
        
        log.info("Generating GST return {} for shop {} period {}", returnType, shopId, period);
        
        // Get summary first
        GstSummaryResponse summary = gstReturnService.getSummary(shopId, period);
        
        // Save return record
        GstReturnDto response = gstReturnService.saveGstReturn(shopId, returnType, period, summary, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * List all GST returns for the shop.
     */
    @GetMapping("/returns")
    public ResponseEntity<ApiResponse<GstReturnListResponse>> listReturns(HttpServletRequest httpRequest) {
        String shopId = getShopId(httpRequest);
        
        log.info("Listing GST returns for shop {}", shopId);
        
        GstReturnListResponse response = gstReturnService.listReturns(shopId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Get a specific GST return.
     */
    @GetMapping("/returns/{returnId}")
    public ResponseEntity<ApiResponse<GstReturnDto>> getReturn(
            @PathVariable String returnId,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        
        log.info("Getting GST return {} for shop {}", returnId, shopId);
        
        GstReturnDto response = gstReturnService.getReturn(returnId)
            .orElseThrow(() -> new IllegalArgumentException("GST Return not found: " + returnId));
        
        // Verify return belongs to shop
        if (!shopId.equals(response.getShopId())) {
            throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to GST return");
        }
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Mark a GST return as filed.
     */
    @PostMapping("/returns/{returnId}/file")
    public ResponseEntity<ApiResponse<GstReturnDto>> markAsFiled(
            @PathVariable String returnId,
            HttpServletRequest httpRequest) {
        
        String shopId = getShopId(httpRequest);
        String userId = getUserId(httpRequest);
        
        log.info("Marking GST return {} as filed for shop {}", returnId, shopId);
        
        // Verify return belongs to shop first
        GstReturnDto existing = gstReturnService.getReturn(returnId)
            .orElseThrow(() -> new IllegalArgumentException("GST Return not found: " + returnId));
        
        if (!shopId.equals(existing.getShopId())) {
            throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to GST return");
        }
        
        GstReturnDto response = gstReturnService.markAsFiled(returnId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    private String getShopId(HttpServletRequest request) {
        String shopId = (String) request.getAttribute("shopId");
        if (!StringUtils.hasText(shopId)) {
            throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
        }
        return shopId;
    }
    
    private String getUserId(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (!StringUtils.hasText(userId)) {
            throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }
        return userId;
    }
}

