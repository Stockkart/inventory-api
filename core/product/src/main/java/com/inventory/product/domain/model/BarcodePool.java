package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shop-scoped generated barcode that can be printed before (or after) attaching to a product.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "barcode_pool")
@CompoundIndexes({
    @CompoundIndex(name = "shop_code_unique_idx", def = "{'shopId': 1, 'code': 1}", unique = true),
    @CompoundIndex(name = "shop_status_idx", def = "{'shopId': 1, 'status': 1}")
})
public class BarcodePool {

  @Id
  private String id;

  @Indexed
  private String shopId;

  /** Scan value (Code128-friendly). */
  private String code;

  private BarcodePoolStatus status;

  /** Set when status is ATTACHED. */
  private String productId;

  /** Optional generation batch label. */
  private String batchId;

  /** Optional sticker text when not yet attached to a product. */
  private String labelName;
  private String labelCompany;
  private BigDecimal labelPrice;

  private Instant createdAt;
  private Instant updatedAt;
}
