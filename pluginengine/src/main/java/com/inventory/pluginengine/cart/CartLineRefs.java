package com.inventory.pluginengine.cart;

import com.inventory.pluginengine.ref.SellableRef;
import org.springframework.util.StringUtils;

public final class CartLineRefs {

  private CartLineRefs() {}

  public static String resolveSellableRef(CartLineInput input) {
    if (input == null) {
      return null;
    }
    if (StringUtils.hasText(input.getSellableRef())) {
      return input.getSellableRef().trim();
    }
    return null;
  }

  public static SellableRef parseRequired(CartLineInput input) {
    String encoded = resolveSellableRef(input);
    if (!StringUtils.hasText(encoded)) {
      throw new IllegalArgumentException("sellableRef is required");
    }
    return SellableRef.parse(encoded);
  }
}
