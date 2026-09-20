package com.inventory.plugins.cafe.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A running order: the commercial staging aggregate in front of checkout. It stays OPEN across
 * several rounds, then settles into a Purchase, which remains the financial source of truth.
 */
@Data
@Document(collection = "cafe_orders")
@CompoundIndexes({
  @CompoundIndex(name = "shop_status", def = "{'shopId': 1, 'status': 1}"),
  @CompoundIndex(
      name = "shop_purchase_unique",
      def = "{'shopId': 1, 'purchaseId': 1}",
      unique = true,
      sparse = true)
})
public class CafeOrder {

  @Id private String id;

  @Indexed private String shopId;

  private String verticalId = "cafe";
  private Integer orderNo;
  private CafeOrderType orderType;

  /** DINE_IN only. */
  private String tableLabel;

  /** TAKEAWAY only, allocated by the existing CafeTokenService. */
  private String tokenNo;

  private CafeOrderStatus status;
  private List<CafeOrderLine> lines = new ArrayList<>();

  /**
   * Set when the settlement cart is created, not when it is completed. This is the settle
   * idempotency handle: there are no MongoDB transactions here, so a crash between creating the
   * cart and completing it must leave a retry able to finish rather than bill twice.
   */
  private String purchaseId;

  /** Stamped at create. An order open across midnight keeps its shift, not the calendar day. */
  private String businessDate;

  private String cancelReason;
  private Instant createdAt;
  private String createdBy;
  private Instant updatedAt;
  private String updatedBy;
}
