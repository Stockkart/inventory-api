package com.inventory.pluginengine.cart;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CheckoutCompletionContext {

  private String shopId;
  private String purchaseId;
  private String verticalId;
  /** Token already assigned when quotation was opened (cafe). */
  private String existingTokenNo;
  private BigDecimal grandTotal;
  private List<CompletedCartLine> lines;

  @Data
  @Builder
  public static class CompletedCartLine {
    private String sellableRef;
    private String stockRef;
    private CartSellMode sellMode;
    private String name;
    private Integer baseQuantity;
    private BigDecimal quantity;
    private Integer unitFactor;
    private BigDecimal costPrice;
  }
}
