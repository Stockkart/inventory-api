package com.inventory.product.cardlayout;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardSurfaceContributor;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Knows which card surfaces exist for a vertical and what their default layouts are
 * (configurable-product-card Req 2.1, 2.2, 2.4, 5.4).
 *
 * <p>Surfaces are the core set followed by whatever the shop's vertical plugin contributes, in that
 * order. Default layouts come from the plugin override when present, else the surface's core
 * default. Plugins are reached only through {@link PluginRegistry}.
 */
@Component
public class CardSurfaceRegistry {

  private final PluginRegistry pluginRegistry;

  public CardSurfaceRegistry(PluginRegistry pluginRegistry) {
    this.pluginRegistry = pluginRegistry;
  }

  /** Core surfaces (minus those the vertical hides) followed by the vertical's own; ids are unique. */
  public List<CardSurfaceDefinition> surfacesFor(String verticalId) {
    Optional<CardSurfaceContributor> contributor = contributor(verticalId);
    Set<String> hidden = contributor.map(CardSurfaceContributor::hiddenCoreSurfaceIds).orElse(Set.of());
    List<CardSurfaceDefinition> result = new ArrayList<>();
    for (CardSurfaceDefinition core : CardLayoutDefaults.coreSurfaces()) {
      if (!hidden.contains(core.surfaceId())) {
        result.add(core);
      }
    }
    Set<String> seen = new HashSet<>();
    CardLayoutDefaults.coreSurfaces().forEach(s -> seen.add(s.surfaceId()));
    contributor
        .map(CardSurfaceContributor::surfaces)
        .orElse(List.of())
        .forEach(
            s -> {
              if (!seen.add(s.surfaceId())) {
                throw new IllegalStateException(
                    "Vertical "
                        + verticalId
                        + " contributes card surface '"
                        + s.surfaceId()
                        + "' which already exists");
              }
              result.add(s);
            });
    return List.copyOf(result);
  }

  /** A surface available to the vertical, by id. */
  public Optional<CardSurfaceDefinition> find(String verticalId, String surfaceId) {
    if (surfaceId == null) {
      return Optional.empty();
    }
    return surfacesFor(verticalId).stream().filter(s -> surfaceId.equals(s.surfaceId())).findFirst();
  }

  /** Ids of every surface available to the vertical, in order. */
  public List<String> surfaceIdsFor(String verticalId) {
    return surfacesFor(verticalId).stream().map(CardSurfaceDefinition::surfaceId).toList();
  }

  /**
   * The default layout for a surface and variant: the vertical plugin's override when it has one,
   * otherwise the surface's core default (Req 5.4).
   */
  public CardLayout defaultLayout(CardSurfaceDefinition surface, String verticalId, CardVariant variant) {
    CardVariant effective = surface.supports(variant) ? variant : CardVariant.REGULAR;
    return contributor(verticalId)
        .flatMap(c -> c.defaultLayout(surface.surfaceId(), effective))
        .orElseGet(() -> surface.coreDefault(effective));
  }

  private Optional<CardSurfaceContributor> contributor(String verticalId) {
    return pluginRegistry.find(verticalId).flatMap(p -> p.getCardSurfaceContributor());
  }
}
