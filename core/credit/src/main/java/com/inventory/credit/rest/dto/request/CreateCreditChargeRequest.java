package com.inventory.credit.rest.dto.request;

import com.inventory.credit.domain.model.CreditPartyType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreateCreditChargeRequest {

  @NotNull private CreditPartyType partyType;

  /** Customer/Vendor id from existing master table. */
  @NotBlank private String partyId;

  @NotBlank private String partyDisplayName;

  private String partyPhone;

  @NotNull
  @DecimalMin(value = "0.01")
  private BigDecimal amount;

  private String note;
  private String referenceType;
  private String referenceId;
  private String sourceKey;
}
