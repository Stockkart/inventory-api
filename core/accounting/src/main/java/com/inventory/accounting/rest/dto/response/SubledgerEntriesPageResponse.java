package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
public class SubledgerEntriesPageResponse {

  private List<EntryRow> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;

  @Data
  @AllArgsConstructor
  public static class EntryRow {
    private String id;
    private String journalEntryId;
    private int journalLineNo;
    private PartyType partyType;
    private String partyId;
    private String kind;
    private BigDecimal amount;
    private String memo;
    private Instant journalDate;
    private Instant postedAt;
    private String postedByUserId;
    private String journalSourceKey;
  }
}
