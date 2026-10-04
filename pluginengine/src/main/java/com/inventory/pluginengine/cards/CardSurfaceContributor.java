package com.inventory.pluginengine.cards;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Extension point through which a vertical plugin shapes product cards.
 *
 * <p>A vertical may add surfaces of its own (the cafe's ingredient search) and may override the
 * default layout of any surface — its own or a core one — for shops on that vertical. Core product
 * code resolves contributors via {@code PluginRegistry}; never by concrete class name.
 *
 * <p>Both methods are optional; the default implementation contributes nothing.
 */
public interface CardSurfaceContributor {

  /** Surfaces this vertical adds. Order is preserved after the core surfaces. */
  default List<CardSurfaceDefinition> surfaces() {
    return List.of();
  }

  /**
   * Core surface ids this vertical replaces with its own and therefore hides from shops on this
   * vertical (the cafe's Ingredient search stands in for Product search). Hidden surfaces are not
   * listed, cannot be saved, and are never served to the shop.
   */
  default Set<String> hiddenCoreSurfaceIds() {
    return Set.of();
  }

  /**
   * Default layout override for a surface and variant. Empty means "use the surface's core
   * default". Implementations should return a stable instance; the layout is treated as immutable.
   */
  default Optional<CardLayout> defaultLayout(String surfaceId, CardVariant variant) {
    return Optional.empty();
  }
}
