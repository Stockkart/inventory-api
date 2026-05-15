package com.inventory.credit.rest.dto.response;

import com.inventory.credit.domain.model.CreditDirection;
import com.inventory.credit.domain.model.CreditEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreditEntryResponse {

  private String id;
  private String accountId;
  private CreditEntryType entryType;
  private CreditDirection direction;
  private BigDecimal amount;
  private BigDecimal balanceAfter;
  private String note;
  private String referenceType;
  private String referenceId;
  private String sourceKey;
  private String paymentMethod;
  private String bankRef;
  private LocalDate txnDate;
  private String createdByUserId;
  private Instant createdAt;
}
