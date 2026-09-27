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
   * Cafe kitchen punches against this cart, in the order they were pressed.
   *
   * <p>Embedded, and written by {@code plugins/cafe}'s {@code CafeCartPuncher} as raw BSON, so
   * that a punch and the line advance it caused are one atomic operation: there is no
   * {@code MongoTransactionManager} here, and a crash between two writes would advance the lines
   * while losing the deltas forever.
   *
   * <p>It must be a mapped property and not merely a key the raw pipeline invents: {@code
   * MongoRepository.save} is a full-document replace, so an unmapped array is silently dropped by
   * every ordinary save of this document. Three things rest on it surviving: the claim's {@code
   * $ne} idempotency, a replay's deltas (which are read off this record, never off the
   * already-advanced lines), and a ticket's round number, which is this list's index of the punch
   * plus one.
   *
   * <p>Left null rather than initialised to an empty list: a grocery, medical or sports bill
   * should carry no cafe key at all.
   */
  private List<CafeKotPunch> cafeKotPunches;

  private Instant createdAt;
  private Instant updatedAt;
}

