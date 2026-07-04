package com.inventory.pluginengine;

import com.inventory.pluginengine.capabilities.VerticalUiContributor;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.pluginengine.cart.CheckoutCompletionHandler;
import com.inventory.pluginengine.cart.QuotationCreateHandler;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import java.util.Optional;

/**
 * Contract for a vertical plugin (medical, apparel, cafe, …).
 * Core product code resolves plugins via {@link PluginRegistry} — never by concrete class name.
 */
public interface VerticalPlugin {

  String getVerticalId();

  /** Plugin module semver — informational; shop pins schema version via {@code Shop.pluginVersion}. */
  String getPluginVersion();

  default Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.empty();
  }

  default Optional<InventoryExtensionRepository> getInventoryExtensionRepository() {
    return Optional.empty();
  }

  default Optional<InventorySearchProvider> getSearchProvider() {
    return Optional.empty();
  }

  default Optional<CartLineContributor> getCartLineContributor() {
    return Optional.empty();
  }

  default Optional<CheckoutCompletionHandler> getCheckoutCompletionHandler() {
    return Optional.empty();
  }

  default Optional<QuotationCreateHandler> getQuotationCreateHandler() {
    return Optional.empty();
  }

  default Optional<MenuVerticalValidator> getMenuVerticalValidator() {
    return Optional.empty();
  }

  default Optional<VerticalUiContributor> getUiContributor() {
    return Optional.empty();
  }

  default Optional<VerticalPricingPolicy> getPricingPolicy() {
    return Optional.empty();
  }
}
