package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.PurchaseStatus;
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
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "purchases")
@CompoundIndexes({
    @CompoundIndex(
        name = "shop_customer_status_soldAt",
        def = "{'shopId': 1, 'customerId': 1, 'status': 1, 'soldAt': -1}")
})
public class Purchase {

  @Id
  private String id;
  /** System-wide unique money transaction id (UUID); set when sale is COMPLETED. */
  @Indexed(unique = true, sparse = true)
  private String txnId;
  private String invoiceNo;
  private String businessType;
  private BillingMode billingMode;
  /**
   * SALE (default / legacy null) vs ESTIMATE. Orthogonal to {@link #billingMode} and {@link
   * #status}.
   */
  private DocumentType documentType;
  /** Only set when {@link #documentType} is ESTIMATE. */
  private EstimateState estimateState;
  /** Assigned estimate series number (e.g. EST-00012); independent of invoiceNo. */
  private String estimateNo;
  /** Set when this estimate was converted into a sale cart/invoice. */
  private String convertedToPurchaseId;
  /** On a SALE cart created from an estimate — points back to the source estimate. */
  private String sourceEstimateId;
  private String userId;
  private String shopId;
  private List<PurchaseItem> items;
  private BigDecimal subTotal;
  private BigDecimal taxTotal;
  private BigDecimal sgstAmount; // Calculated SGST amount
  private BigDecimal cgstAmount; // Calculated CGST amount
  private BigDecimal discountTotal;
  private BigDecimal saleAdditionalDiscountTotal;
  private BigDecimal grandTotal;
  /** Margin breakdown: total cost (inventory cost price × quantities). */
  private BigDecimal totalCost;
  /** Revenue before tax (taxable value): subTotal − additionalDiscountTotal. */
  private BigDecimal revenueBeforeTax;
  /** Revenue after tax (total amount received): grandTotal. */
  private BigDecimal revenueAfterTax;
  /** Total profit: revenueBeforeTax − totalCost. */
  private BigDecimal totalProfit;
  /** Overall margin percentage: (totalProfit / revenueBeforeTax) × 100. */
  private BigDecimal marginPercent;
  private Instant soldAt;
  private boolean valid;
  private String paymentMethod;
  /** Cash leg at checkout completion (split payment). */
  private BigDecimal cashAmount;
  /** Online leg at checkout completion (split payment). */
  private BigDecimal onlineAmount;
  /** Credit leg at checkout completion (customer due). */
  private BigDecimal creditAmount;
  private PurchaseStatus status;
  private String customerId;
  private String customerName; // Used when only name is provided without phone
  /** Daily order token (cafe vertical). */
  private String tokenNo;

  /** Dine-in table, free text. There is no table registry. */
  private String tableLabel;

  /**
   * Cancellations owed to the kitchen for this bill's lines, one entry per {@code
   * CafeKotCancelService.cancel} call. Written by {@code plugins/cafe} as raw BSON; see {@link
   * CafeKotCancel} for why and for the idempotency shape.
   */
  private List<CafeKotCancel> cafeKotCancels;

  /**
   * The cafe flushes this bill has already absorbed, in the order it absorbed them — written by
   * {@code plugins/cafe}'s {@code CafeFlushService} as raw BSON, exactly as {@link
   * #cafeKotCancels} is, because that module cannot depend on this one.
   *
   * <p>It must be a mapped property and not merely a key the raw {@code Update} invents: {@code
   * MongoRepository.save} is a full-document replace, so an unmapped array is silently dropped by
   * every ordinary save of this document — add-to-cart, the quotation token backfill, checkout
   * completion. Two things rest on it surviving: the append's {@code $ne} idempotency (a bill that
   * forgot a flush absorbs it twice), and a ticket's round number, which is this list's index of
   * the flush plus one (a forgotten list prints "Round 1" twice for one token).
   */
  private List<String> cafeFlushIds;

  private Instant createdAt;
  private Instant updatedAt;
}

