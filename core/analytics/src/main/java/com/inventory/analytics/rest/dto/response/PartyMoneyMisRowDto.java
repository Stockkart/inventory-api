package com.inventory.analytics.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartyMoneyMisRowDto {
  private String txnId;
  private String txnType;
  private String txnTypeLabel;
  private String partyId;
  private String partyName;
  private LocalDate txnDate;
  private Instant postedAt;
  private String refNo;
  private String againstTxnId;
  private String againstRefNo;
  private BigDecimal totalAmount;
  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  private BigDecimal creditAmount;
  private BigDecimal balanceAfter;
  private String sourceType;
  private String sourceId;
  /** True for synthetic opening-balance rows. */
  private boolean opening;
}
