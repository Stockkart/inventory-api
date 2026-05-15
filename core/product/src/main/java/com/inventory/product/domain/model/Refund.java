package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Domain model for refunds.
 * Tracks refunds linked to purchases.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "refunds")
public class Refund {

  @Id
  private String id;

  /**
   * Purchase ID that this refund belongs to.
   */
  private String purchaseId;

  /**
   * Human-readable credit note number for GST / receipts (e.g. CN-00001). Assigned at creation.
   */
  private String creditNoteNo;

  /**
   * Shop ID where the refund was processed.
   */
  private String shopId;

  /**
   * User ID who processed the refund.
   */
  private String userId;

  /**
   * List of items that were refunded.
   */
  private List<RefundItem> refundedItems;

  /**
   * Total refund amount (credit note total, typically whole rupees).
   */
  private BigDecimal refundAmount;

  private BigDecimal taxableTotal;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
  private BigDecimal cogsTotal;
  private BigDecimal roundOff;

  private String customerId;
  private BigDecimal refundCash;
  private BigDecimal refundOnline;
  private BigDecimal refundToCredit;
  private String paymentMethod;

  /**
   * Number of items refunded.
   */
  private Integer totalItemsRefunded;

  /**
   * Optional reason or notes for the refund.
   */
  private String reason;

  /**
   * Timestamp when the refund was created.
   */
  private Instant createdAt;

  /**
   * Timestamp when the refund was last updated.
   */
  private Instant updatedAt;
}

