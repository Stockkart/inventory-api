package com.inventory.product.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shop-level rate rules: the ordered list that generates each batch's named rates. The unique
 * {@code shopId} index is created by {@code RateRulesIndexMigration}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_rate_rules")
public class ShopRateRulesDocument {

  @Id
  private String id;

  private String shopId;

  private List<RateRule> rules = new ArrayList<>();

  private Instant createdAt;

  private Instant updatedAt;

  private String updatedByUserId;
}
