package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.product.rest.dto.request.BulkCreateInventoryRequest;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.InventoryIdsRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.rest.dto.response.BulkCreateInventoryResponse;
import com.inventory.product.rest.dto.response.InventoryDetailResponse;
import com.inventory.product.rest.dto.response.InventoryListResponse;
import com.inventory.product.rest.dto.response.InventoryReceiptResponse;
import com.inventory.product.rest.dto.response.LotDetailDto;
import com.inventory.product.rest.dto.response.LotListResponse;
import com.inventory.product.rest.dto.response.ParsedInventoryListResponse;
import com.inventory.product.service.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class InventoryController {

  @Autowired
  private InventoryService inventoryService;


  @PostMapping
  public ResponseEntity<ApiResponse<InventoryReceiptResponse>> create(
      @RequestBody CreateInventoryRequest request,
      HttpServletRequest httpRequest) {
    // Get userId and shopId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.create(request, userId, shopId)));
  }

  /**
   * Bulk create inventory items with shared vendorId; stock-in is keyed by vendor purchase invoice id.
   *
   * @param bulkRequest the bulk creation request containing list of items and shared vendorId
   * @param httpRequest HTTP request containing shopId from authentication
   * @return bulk creation response with created items
   */
  @PostMapping(value = "/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<BulkCreateInventoryResponse>> bulkCreate(
      @RequestBody BulkCreateInventoryRequest bulkRequest,
      HttpServletRequest httpRequest) {
    // Get userId and shopId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    // Validate request
    if (bulkRequest.getItems() == null || bulkRequest.getItems().isEmpty()) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.error("Items list cannot be empty"));
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.bulkCreate(bulkRequest, userId, shopId)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<InventoryListResponse>> list(
    HttpServletRequest httpRequest,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size
  ) {
    // Get shopId from request attributes to ensure user can only access their shop's inventory
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
        ErrorCode.UNAUTHORIZED,
        "Unauthorized access to shop inventory");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.list(shopId, page, size)));
  }

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<InventoryListResponse>> search(
      @RequestParam("q") String q,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes to ensure user can only search their shop's inventory
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop inventory");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.search(shopId, q)));
  }

  @PostMapping("/by-ids")
  public ResponseEntity<ApiResponse<List<InventoryDetailResponse>>> getByIds(
      @RequestBody InventoryIdsRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop inventory");
    }
    List<String> ids = request != null ? request.getInventoryIds() : List.of();
    return ResponseEntity.ok(ApiResponse.success(inventoryService.getByIds(ids, shopId)));
  }

  @GetMapping("/lots/search")
  public ResponseEntity<ApiResponse<LotListResponse>> searchLots(
    @RequestParam("q") String q,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size,
    HttpServletRequest httpRequest
  ) {
    String shopId = (String) httpRequest.getAttribute("shopId");

    return ResponseEntity.ok(
      ApiResponse.success(inventoryService.searchLots(shopId, q, page, size))
    );
  }


  @GetMapping("/{lotId}")
  public ResponseEntity<ApiResponse<InventoryDetailResponse>> getLot(@PathVariable String lotId) {
    return ResponseEntity.ok(ApiResponse.success(inventoryService.getLot(lotId)));
  }

  /**
   * List all lots for the authenticated shop.
   * Optionally filter by search query.
   *
   * @param search optional search query to filter lots by lotId
   * @param httpRequest HTTP request containing shopId from authentication
   * @return list of lot summaries
   */
  @GetMapping("/lots")
  public ResponseEntity<ApiResponse<LotListResponse>> listLots(
      @RequestParam(required = false) String search,
      HttpServletRequest httpRequest,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    // Get shopId from request attributes to ensure user can only access their shop's lots
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop lots");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.listLots(shopId, search, page, size)));
  }

  /**
   * Get details of a specific lot including all products in that lot.
   *
   * @param lotId the lot ID
   * @param httpRequest HTTP request containing shopId from authentication
   * @return lot details with all products
   */
  @GetMapping("/lots/{lotId}")
  public ResponseEntity<ApiResponse<LotDetailDto>> getLotDetails(
      @PathVariable String lotId,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes to ensure user can only access their shop's lots
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop lots");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.getLotDetails(lotId, shopId)));
  }

  @GetMapping("/low-stock")
  public ResponseEntity<ApiResponse<InventoryListResponse>> lowStock(
    HttpServletRequest httpRequest,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size
  ) {
    String shopId = (String) httpRequest.getAttribute("shopId");

    return ResponseEntity.ok(
      ApiResponse.success(inventoryService.getLowStockItems(shopId, page, size))
    );
  }

  @PutMapping("/{inventoryId}")
  public ResponseEntity<ApiResponse<InventoryDetailResponse>> update(
      @PathVariable String inventoryId,
      @RequestBody UpdateInventoryRequest request,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop inventory");
    }

    return ResponseEntity.ok(ApiResponse.success(inventoryService.update(inventoryId, request, shopId)));
  }

  /**
   * Parse invoice image and extract inventory items using OCR.
   *
   * @param image the invoice image file to process
   * @param httpRequest HTTP request containing shopId from authentication
   * @return list of parsed inventory items from the invoice
   */
  @PostMapping(value = "/parse-invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<ParsedInventoryListResponse>> parseInvoice(
      @RequestParam("image") MultipartFile image,
      HttpServletRequest httpRequest) {
    log.info("Received invoice parsing request for image: {}, size: {} bytes",
        image.getOriginalFilename(), image.getSize());

    ParsedInventoryListResponse response = inventoryService.parseInvoiceImage(image);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Parse Excel stock sheet for migration from legacy apps.
   * Supports .xls and .xlsx with auto-detected column mapping.
   * Returns parsed items ready for review and bulk create.
   *
   * @param file the Excel file (.xls or .xlsx) to parse
   * @return list of parsed inventory items
   */
  @PostMapping(value = "/parse-stock-sheet", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<ParsedInventoryListResponse>> parseStockSheet(
      @RequestParam("file") MultipartFile file,
      HttpServletRequest httpRequest) {
    log.info("Received stock sheet parse request for file: {}, size: {} bytes",
        file.getOriginalFilename(), file.getSize());

    ParsedInventoryListResponse response = inventoryService.parseStockSheet(file);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

}
