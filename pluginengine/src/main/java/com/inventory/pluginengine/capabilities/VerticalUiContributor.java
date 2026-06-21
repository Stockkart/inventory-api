package com.inventory.pluginengine.capabilities;

import com.inventory.pluginengine.schema.VerticalSchema;

/** Publishes server-side UI capabilities for {@code GET /shops/me/capabilities}. */
public interface VerticalUiContributor {

  String getVerticalId();

  ShopUiCapabilities contribute(VerticalSchema schema);
}
