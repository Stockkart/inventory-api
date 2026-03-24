package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.SchemeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseItem {

  private String inventoryId;
  private String name;
  private BillingMode billingMode;
  private BigDecimal quantity;
  /** Sale/display unit for quantity (e.g. STRIP, PACK). */
  private String saleUnit;
  /** Quantity converted to inventory base unit, used for stock deductions. */
  private Integer baseQuantity;
  /** Base units represented by one quantity unit (e.g. 10 for strip of 10 tabs). */
  private Integer unitFactor;
  /** Available units for this inventory line (base + conversion), used by FE unit picker. */
  private List<AvailableUnit> availableUnits;
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private BigDecimal discount;
  private BigDecimal saleAdditionalDiscount; // Additional discount percentage from inventory
  private BigDecimal totalAmount; // Final amount after saleAdditionalDiscount and taxes (CGST + SGST)
  private String sgst; // SGST rate from inventory (e.g., "9" for 9%)
  private String cgst; // CGST rate from inventory (e.g., "9" for 9%)
  /** Cost price per unit (from inventory at time of sale). Used for margin/profit breakdown. */
  private BigDecimal costPrice;
  /** Total cost for this line: costPrice × billable quantity. */
  private BigDecimal costTotal;
  /** Profit for this line: revenue before tax − costTotal. */
  private BigDecimal profit;
  /** Margin percentage on this line: (profit / revenue before tax) × 100. */
  private BigDecimal marginPercent;
  /**
   * Scheme type for selling:
   * - FIXED_UNITS: use schemePayFor / schemeFree (e.g. "2 free on 10").
   * - PERCENTAGE: use schemePercentage (e.g. 10 = 10% extra free).
   */
  private SchemeType schemeType;
  /** Scheme for selling: pay for this many (e.g. 10). With schemeFree = 2, "2 free on 10". Billing uses paid quantity. */
  private Integer schemePayFor;
  /** Scheme for selling: free units per batch (e.g. 2). With schemePayFor = 10, "2 free on 10". */
  private Integer schemeFree;
  /** Scheme percentage for selling when schemeType is PERCENTAGE (e.g. 10 = 10% extra free). */
  private BigDecimal schemePercentage;

  /** From registration: additional discount % from Pricing. Read-only at sale. */
  @Transient
  private BigDecimal purchaseAdditionalDiscount;
  /** From registration: scheme type from Inventory. Read-only at sale. */
  @Transient
  private SchemeType purchaseSchemeType;
  /** From registration: scheme pay-for from Inventory. Read-only at sale. */
  @Transient
  private Integer purchaseSchemePayFor;
  /** From registration: scheme free from Inventory. Read-only at sale. */
  @Transient
  private Integer purchaseSchemeFree;
  /** From registration: scheme percentage from Inventory when schemeType PERCENTAGE. Read-only at sale. */
  @Transient
  private BigDecimal purchaseSchemePercentage;
}

