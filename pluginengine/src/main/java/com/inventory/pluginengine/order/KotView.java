package com.inventory.pluginengine.order;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** One kitchen ticket, as core sees it. */
@Data
@Builder
public class KotView {

  private String kotId;
  private String shopId;
  private String orderId;
  private Integer kotNo;
  private String department;
  private Integer roundNo;

  /** ISSUED or VOIDED. ISSUED means created by the backend, not physically printed. */
  private String status;

  private String voidReason;
  private Integer reprintCount;
  private String businessDate;
  private List<KotLineView> lines;
}
