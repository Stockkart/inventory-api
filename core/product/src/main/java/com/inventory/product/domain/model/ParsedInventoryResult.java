package com.inventory.product.domain.model;

import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "parsed_inventory_results")
public class ParsedInventoryResult {
  
  @Id
  private String id;
  private String uploadTokenId;
  private String userId;
  private String shopId;
  private List<CreateInventoryItemRequest> parsedItems;
  private Instant createdAt;
}
