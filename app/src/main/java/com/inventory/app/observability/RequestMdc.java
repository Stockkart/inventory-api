package com.inventory.app.observability;

public final class RequestMdc {

  public static final String REQUEST_ID = "requestId";
  public static final String SHOP_ID = "shopId";
  public static final String USER_ID = "userId";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  private RequestMdc() {}
}
