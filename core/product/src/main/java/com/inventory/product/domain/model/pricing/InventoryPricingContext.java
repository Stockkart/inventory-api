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
    /** The user making an update; checked for the grant to edit generated rates. Null on create. */
    public final String userId;

    Context(Type type, CreateInventoryRequest createRequest,
        UpdateInventoryRequest updateRequest, String shopId, String userId) {
      this.type = type;
      this.createRequest = createRequest;
      this.updateRequest = updateRequest;
      this.shopId = shopId;
      this.userId = userId;
    }
  }

  public static void setCreate(CreateInventoryRequest request, String shopId) {
    if (request != null && StringUtils.hasText(shopId)) {
      HOLDER.set(new Context(Type.CREATE, request, null, shopId, null));
    }
  }

  public static void setUpdate(UpdateInventoryRequest request, String userId) {
    if (request != null) {
      HOLDER.set(new Context(Type.UPDATE, null, request, null, userId));
    }
  }

  public static Context get() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }
}
