package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A code that grants or discounts one add-on or a plan. The collection name is historical: it holds
 * add-on and plan vouchers. The counters enforce the cap; {@code voucher_redemptions} is the record
 * of who used it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "add_on_vouchers")
public class AddOnVoucher {

  @Id
  private String id;
  /** Uppercase, unique. */
  private String code;
  /** What the voucher applies to; null on stored rows means {@link VoucherTarget#ADD_ON}. */
  private VoucherTarget target;
  private String addOnCode;
  /** The plan a PLAN voucher discounts; unused for add-on vouchers. */
  private String planCode;
  private VoucherType type;
  /** Percent for PERCENT_OFF, rupees for FLAT_OFF; unused for FREE_ADDON. */
  private BigDecimal value;
  /** Add-on units the voucher covers; a voucher-added line has this quantity. */
  private int quantity;
  /** Global cap; null means no cap. */
  private Integer maxRedemptions;
  /** Slots held by unpaid orders. */
  private int reservedCount;
  /** Slots used by paid orders. */
  private int redemptionCount;
  /** One use per shop, enforced by a unique index on live redemptions (r4.9). */
  private boolean singleUsePerShop;
  /** Only this shop may use it; null means any shop. */
  private String issuedToShopId;
  private Instant validFrom;
  private Instant validTo;
  private Boolean active;
  private String note;
  /** Codes generated together share a batch. */
  private String batchId;
  private String createdByUserId;
  private Instant createdAt;
  private Instant updatedAt;

  public VoucherTarget effectiveTarget() {
    return target == null ? VoucherTarget.ADD_ON : target;
  }

  public boolean isPlanVoucher() {
    return effectiveTarget() == VoucherTarget.PLAN;
  }

  public boolean isActive() {
    return !Boolean.FALSE.equals(active);
  }
}
