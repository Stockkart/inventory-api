package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.ItemType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Catalog identity returned by product suggest / get-by-id. Used by the registration UI to
 * prefill identity fields (pricing and lot fields are intentionally excluded).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductSuggestionDto {
  private String id;
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private String businessType;
  private ItemType itemType;
  private Integer itemTypeDegree;
  private String baseUnit;
  private UnitConversion unitConversions;
  private String hsn;
}
