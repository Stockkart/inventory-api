package com.inventory.pluginengine;

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
}
