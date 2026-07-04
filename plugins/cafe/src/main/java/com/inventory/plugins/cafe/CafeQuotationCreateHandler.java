package com.inventory.plugins.cafe;

import com.inventory.pluginengine.cart.QuotationCreateContext;
import com.inventory.pluginengine.cart.QuotationCreateHandler;
import com.inventory.pluginengine.cart.QuotationCreateResult;
import org.springframework.stereotype.Component;

@Component
public class CafeQuotationCreateHandler implements QuotationCreateHandler {

  private final CafeTokenService cafeTokenService;

  public CafeQuotationCreateHandler(CafeTokenService cafeTokenService) {
    this.cafeTokenService = cafeTokenService;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public QuotationCreateResult onQuotationCreated(QuotationCreateContext context) {
    String tokenNo = cafeTokenService.allocateToken(context.getShopId());
    return QuotationCreateResult.builder().tokenNo(tokenNo).build();
  }
}
