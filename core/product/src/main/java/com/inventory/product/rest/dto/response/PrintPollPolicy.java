package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How long and how often the browser watches the bridge's job history before reporting. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintPollPolicy {
  private long intervalMs;
  private long budgetMs;
}
