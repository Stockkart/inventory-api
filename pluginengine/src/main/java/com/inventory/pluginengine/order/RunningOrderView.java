package com.inventory.pluginengine.order;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A running order, as core sees it.
 *
 * <p>Enums are carried as strings deliberately: the concrete statuses belong to the implementing
 * plugin, and core must not depend on a vertical's domain types.
 */
@Data
@Builder
public class RunningOrderView {

  private String orderId;
  private String shopId;
  private Integer orderNo;
  private String orderType;
  private String tableLabel;
  private String tokenNo;
  private String status;
  private String purchaseId;
  private String businessDate;

  /** How many rounds have been punched. Counted by the backend; never derived client-side. */
  private Integer roundsPunched;

  private List<RunningOrderLineView> lines;
}
