package com.inventory.accounting.rest.dto.request;

import com.inventory.accounting.domain.model.PartyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
public class OpeningBalanceRequest {

  private LocalDate txnDate;

  @Size(max = 500)
  private String narration;

  @NotEmpty
  @Valid
  @Size(min = 2)
  private List<Line> lines;

  @Data
  public static class Line {
    private String accountCode;
    private String accountId;
    private BigDecimal debit;
    private BigDecimal credit;
    private PartyType partyType;
    private String partyRefId;
    private String partyDisplayName;
    @Size(max = 280) private String memo;
  }
}
