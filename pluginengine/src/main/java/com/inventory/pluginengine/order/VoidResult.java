package com.inventory.pluginengine.order;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * The outcome of one void operation.
 *
 * <p>The voided lines are the ones voided by <em>this</em> operation, not every line that happens
 * to be voided now. Voiding A then B must produce two slips naming A and B separately; a document
 * derived later from current state would name A+B twice.
 */
@Data
@Builder
public class VoidResult {

  private KotView kot;

  /** Identifies this operation, so its slip can be re-fetched after a printer jam. */
  private String voidBatchId;

  private String reason;

  /** Only the lines this operation voided. */
  private List<KotLineView> voidedLines;

  /** True when this operation voided the ticket's last active line. */
  private boolean ticketFullyVoided;
}
