package com.inventory.product.domain.model.pricing;

import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import org.springframework.util.StringUtils;

/**
 * Thread-local context for pricing writes during inventory create/update.
 */
public final class InventoryPricingContext {

  private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

  public enum Type { CREATE, UPDATE }

  public static class Context {
    public final Type type;
    public final CreateInventoryRequest createRequest;
    public final UpdateInventoryRequest updateRequest;
    public final String shopId;

    Context(Type type, CreateInventoryRequest createRequest,
        UpdateInventoryRequest updateRequest, String shopId) {
      this.type = type;
      this.createRequest = createRequest;
      this.updateRequest = updateRequest;
      this.shopId = shopId;
    }
  }

  public static void setCreate(CreateInventoryRequest request, String shopId) {
    if (request != null && StringUtils.hasText(shopId)) {
      HOLDER.set(new Context(Type.CREATE, request, null, shopId));
    }
  }

  public static void setUpdate(UpdateInventoryRequest request) {
    if (request != null) {
      HOLDER.set(new Context(Type.UPDATE, null, request, null));
    }
  }

  public static Context get() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }
}
