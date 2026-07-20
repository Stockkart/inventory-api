package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.ItemType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Shop-scoped catalog identity for a SKU. One Product maps to many active {@link Inventory} lots.
 *
 * <p>Holds only stable "what it is" attributes. Per-lot data (counts, batch, expiry, vendor,
 * pricing) and lot-level policy ({@code billingMode}, {@code discountApplicable},
 * {@code thresholdCount}) live on {@link Inventory}. Editing any identity field at registration
 * forks a new Product rather than mutating the shared one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "product")
@CompoundIndexes({
    @CompoundIndex(
        name = "shop_identity_idx",
        def = "{'shopId': 1, 'normalizedName': 1, 'companyName': 1, 'baseUnit': 1}")
})
public class Product {

  @Id
  private String id;

  @Indexed
  private String shopId;

  private String barcode;
  private String name;
  /** Lower-cased, trimmed {@link #name} used for suggest + identity matching. */
  private String normalizedName;
  private String description;
  private String companyName;
  private String businessType;
  private ItemType itemType;
  /** When itemType is DEGREE, e.g. 8 for "8 deg", 24 for "24 deg". */
  private Integer itemTypeDegree;
  /** Base stock unit (e.g. TAB, ML, BOTTLE). */
  private String baseUnit;
  /** Pack conversion where factor is base units in 1 pack. */
  private UnitConversion unitConversions;
  private String hsn;

  private Instant createdAt;
  private Instant updatedAt;
}
