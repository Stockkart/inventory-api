package com.inventory.pluginengine;

import java.util.Optional;

/**
 * Base for vertical plugins whose {@code id} and {@code version} come from {@code application.properties}
 * (e.g. {@code vertical.plugins.medical.id}).
 */
public abstract class ConfiguredVerticalPlugin implements VerticalPlugin {

  private final String verticalId;
  private final String pluginVersion;

  protected ConfiguredVerticalPlugin(String verticalId, String pluginVersion) {
    this.verticalId = verticalId;
    this.pluginVersion = pluginVersion;
  }

  @Override
  public String getVerticalId() {
    return verticalId;
  }

  @Override
  public String getPluginVersion() {
    return pluginVersion;
  }

  @Override
  public abstract Optional<InventoryVerticalValidator> getInventoryValidator();
}
