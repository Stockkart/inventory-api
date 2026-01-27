package com.inventory.product.util;

import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.rest.dto.inventory.CreateInventoryItemRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Utility class for parsed inventory result operations.
 */
@Component
public class ParsedInventoryUtil {

  /**
   * Create a ParsedInventoryResult from parsed items.
   * 
   * @param uploadTokenId the upload token ID
   * @param userId the user ID
   * @param shopId the shop ID
   * @param parsedItems the parsed inventory items
   * @return the created ParsedInventoryResult
   */
  public ParsedInventoryResult createParsedInventoryResult(
      String uploadTokenId,
      String userId,
      String shopId,
      List<CreateInventoryItemRequest> parsedItems) {
    ParsedInventoryResult result = new ParsedInventoryResult();
    result.setUploadTokenId(uploadTokenId);
    result.setUserId(userId);
    result.setShopId(shopId);
    result.setParsedItems(parsedItems);
    result.setCreatedAt(Instant.now());
    return result;
  }

  /**
   * Convert ParsedInventoryResult to ParsedInventoryListResponse.
   * 
   * @param result the parsed inventory result
   * @return the response DTO
   */
  public com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse toResponse(
      ParsedInventoryResult result) {
    com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse response =
        new com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse();
    response.setItems(result.getParsedItems());
    response.setTotalItems(result.getParsedItems() != null ? result.getParsedItems().size() : 0);
    return response;
  }
}
