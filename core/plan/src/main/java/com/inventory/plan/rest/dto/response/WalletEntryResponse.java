package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.ShopCreditSource;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletEntryResponse {
  private ShopCreditSource source;
  private String sourceId;
  private BigDecimal amount;
  private BigDecimal availableDelta;
  private BigDecimal reservedDelta;
  private BigDecimal availableAfter;
  private BigDecimal reservedAfter;
  private BigDecimal outstandingAfter;
  private String note;
  private Instant createdAt;
}
