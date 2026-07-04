package com.inventory.plugins.cafe;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.pluginengine.capabilities.VerticalUiContributor;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.pluginengine.cart.CheckoutCompletionHandler;
import com.inventory.pluginengine.cart.QuotationCreateHandler;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import com.inventory.plugins.cafe.repository.CafeInventoryExtensionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CafePlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;
  private final CafeInventoryExtensionRepository extensionRepository;
  private final CafeInventorySearchProvider searchProvider;
  private final CafeMenuVerticalValidator menuVerticalValidator;
  private final CafeUiContributor uiContributor;
  private final CafeMenuCartLineContributor menuCartLineContributor;
  private final CafeCheckoutCompletionHandler checkoutCompletionHandler;
  private final CafeQuotationCreateHandler quotationCreateHandler;
  private final CafePricingPolicy cafePricingPolicy;

  public CafePlugin(
      CafeVerticalProperties properties,
      CafeInventoryExtensionRepository extensionRepository,
      CafeInventorySearchProvider searchProvider,
      CafeMenuVerticalValidator menuVerticalValidator,
      CafeUiContributor uiContributor,
      CafeMenuCartLineContributor menuCartLineContributor,
      CafeCheckoutCompletionHandler checkoutCompletionHandler,
      CafeQuotationCreateHandler quotationCreateHandler,
      CafePricingPolicy cafePricingPolicy) {
    super(properties.getId(), properties.getVersion());
    this.inventoryValidator = new SchemaDrivenInventoryValidator(properties.getId());
    this.extensionRepository = extensionRepository;
    this.searchProvider = searchProvider;
    this.menuVerticalValidator = menuVerticalValidator;
    this.uiContributor = uiContributor;
    this.menuCartLineContributor = menuCartLineContributor;
    this.checkoutCompletionHandler = checkoutCompletionHandler;
    this.quotationCreateHandler = quotationCreateHandler;
    this.cafePricingPolicy = cafePricingPolicy;
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }

  @Override
  public Optional<InventoryExtensionRepository> getInventoryExtensionRepository() {
    return Optional.of(extensionRepository);
  }

  @Override
  public Optional<InventorySearchProvider> getSearchProvider() {
    return Optional.of(searchProvider);
  }

  @Override
  public Optional<MenuVerticalValidator> getMenuVerticalValidator() {
    return Optional.of(menuVerticalValidator);
  }

  @Override
  public Optional<VerticalUiContributor> getUiContributor() {
    return Optional.of(uiContributor);
  }

  @Override
  public Optional<CartLineContributor> getCartLineContributor() {
    return Optional.of(menuCartLineContributor);
  }

  @Override
  public Optional<CheckoutCompletionHandler> getCheckoutCompletionHandler() {
    return Optional.of(checkoutCompletionHandler);
  }

  @Override
  public Optional<QuotationCreateHandler> getQuotationCreateHandler() {
    return Optional.of(quotationCreateHandler);
  }

  @Override
  public Optional<VerticalPricingPolicy> getPricingPolicy() {
    return Optional.of(cafePricingPolicy);
  }
}
