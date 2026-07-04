package com.inventory.plugins.cafe;

import com.inventory.pluginengine.cart.CheckoutCompletionContext;
import com.inventory.pluginengine.cart.CheckoutCompletionHandler;
import com.inventory.pluginengine.cart.CheckoutCompletionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class CafeCheckoutCompletionHandler implements CheckoutCompletionHandler {

  private final CafeTokenService cafeTokenService;

  public CafeCheckoutCompletionHandler(CafeTokenService cafeTokenService) {
    this.cafeTokenService = cafeTokenService;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public CheckoutCompletionResult onPurchaseCompleted(CheckoutCompletionContext context) {
    if (StringUtils.hasText(context.getExistingTokenNo())) {
      log.info(
          "Reusing cafe token {} for purchase {}",
          context.getExistingTokenNo(),
          context.getPurchaseId());
      return CheckoutCompletionResult.builder()
          .tokenNo(context.getExistingTokenNo())
          .build();
    }
    String tokenNo = cafeTokenService.allocateToken(context.getShopId());
    log.info("Allocated cafe token {} for purchase {}", tokenNo, context.getPurchaseId());
    return CheckoutCompletionResult.builder().tokenNo(tokenNo).build();
  }
}
