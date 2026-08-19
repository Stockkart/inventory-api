package com.inventory.product.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.pluginengine.ref.SellableRef;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseItem {

  /** Canonical line identity: {@code inventory:lotId} or {@code menu:itemId}. */
  private String sellableRef;

  /** Optional stock target when sale consumes inventory (e.g. cafe direct). */
  private String stockRef;

  /** {@code sku} | {@code menu} | {@code direct}. */
  private String sellMode;

  /** Legacy BSON field for reads before backfill. */
  @JsonIgnore
  @Field("inventoryId")
  private String mongoInventoryId;

  /** Legacy BSON field for reads before backfill. */
  @JsonIgnore
  @Field("menuItemId")
  private String mongoMenuItemId;

  private String name;
  /**
   * HSN recorded on the line at sale time.
   *
   * <p>Normally the lot answers this, but a line can outlive its lot -- stock is
   * consumed, and a migrated sale references a delivery that no longer exists.
   * Where the source states the HSN, keeping it on the line means the tax
   * summary does not depend on the lot still being there.
   */
  private String hsn;
  /**
   * Batch number, marketing company and expiry as printed on the sale.
   *
   * <p>The same reasoning as {@link #hsn}: the lot is normally the answer, but a
   * line outlives its lot and these three are what a pharmacy invoice is read
   * for -- a customer returning a strip is identified by its batch. Recording
   * them on the line keeps a reprint faithful to what was handed over.
   *
   * <p>Expiry is held as printed rather than as a date. It is stated to the
   * month, and parsing it into an instant would invent a day.
   */
  private String batchNo;

  private String companyName;

  private String expiryDate;

  private BillingMode billingMode;
  private BigDecimal quantity;
  private String saleUnit;
  private String baseUnit;
  private String packUnitUqc;
  private Integer baseQuantity;
  private Integer unitFactor;
  private List<AvailableUnit> availableUnits;
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private BigDecimal discount;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal totalAmount;
  private String sgst;
  private String cgst;
  private BigDecimal costPrice;
  private BigDecimal costTotal;
  private BigDecimal profit;
  private BigDecimal marginPercent;
  private SchemeType schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;

  @Transient
  private BigDecimal purchaseAdditionalDiscount;
  @Transient
  private SchemeType purchaseSchemeType;
  @Transient
  private Integer purchaseSchemePayFor;
  @Transient
  private Integer purchaseSchemeFree;
  @Transient
  private BigDecimal purchaseSchemePercentage;

  /** Derived stock lot id for refunds/analytics and API backward compatibility. */
  @JsonProperty("inventoryId")
  public String getInventoryId() {
    return PurchaseItemRefs.stockLotId(this);
  }

  /** Derived menu item id for API backward compatibility. */
  @JsonProperty("menuItemId")
  public String getMenuItemId() {
    PurchaseItemRefs.normalize(this);
    SellableRef ref = SellableRef.parseLenient(sellableRef);
    return ref != null && ref.isMenu() ? ref.id() : null;
  }
}
