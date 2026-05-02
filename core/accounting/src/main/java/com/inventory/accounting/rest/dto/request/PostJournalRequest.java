package com.inventory.accounting.rest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class PostJournalRequest {

  @NotBlank private String description;

  /** Optional business date — defaults to current instant when omitted. */
  private Instant journalDate;

  /** When supplied, repeats return the originally posted journal (idempotency helper). */
  private String sourceKey;

  @NotEmpty @Valid private List<PostJournalLineRequest> lines;
}
