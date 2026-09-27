package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One order's use of a voucher. Unique per (voucher, order). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "voucher_redemptions")
public class VoucherRedemption {

  @Id
  private String id;
  private String voucherId;
  private String voucherCode;
  private String shopId;
  private String orderId;
  private String addOnCode;
  private BigDecimal discount;
  private VoucherRedemptionStatus status;
  /**
   * True while the redemption holds its slot (RESERVED or REDEEMED); unset on release. With
   * {@code singleUsePerShop} it feeds the partial unique index that stops a shop reusing a code.
   */
  private Boolean holdsSlot;
  private boolean singleUsePerShop;
  private Instant reservedAt;
  private Instant redeemedAt;
  private Instant releasedAt;
}
