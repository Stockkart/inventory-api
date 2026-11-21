package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Product-entry estimate draft: vendor-side analogue of sell estimates.
 *
 * <p>OPEN drafts hold lines only. LOCK creates BASIC {@link Inventory} lots (estimate-only sell).
 * CONVERT is completed when Product Entry saves REGULAR stock and links back here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stock_entry_estimates")
@CompoundIndex(name = "shop_state_updated", def = "{'shopId': 1, 'state': 1, 'updatedAt': -1}")
public class StockEntryEstimate {

  @Id
  private String id;

  @Indexed
  private String shopId;
  private String userId;
  /** Human series e.g. PUR-EST-00012. */
  private String estimateNo;
  private StockEntryEstimateState state;

  private String vendorId;
  private String vendorInvoiceNo;
  private Instant vendorInvoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  private String paymentMethod;
  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  private BigDecimal creditAmount;
  private BigDecimal paidAmount;

  private List<StockEntryEstimateLine> lines = new ArrayList<>();

  /** Set after LOCK when BASIC bulk create produced a vendor purchase invoice. */
  private String vendorPurchaseInvoiceId;
  /** Set after CONVERT when REGULAR product entry save completed. */
  private String convertedToVendorPurchaseInvoiceId;

  private Instant lockedAt;
  private String lockedByUserId;
  private Instant createdAt;
  private Instant updatedAt;
}
