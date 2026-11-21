package com.inventory.product.cardlayout;

// Feature: configurable-product-card, Property 2: Surface set is vertical-scoped
// Feature: configurable-product-card, Property 8: Default override precedence

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceContributor;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties 2 and 8.
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 5.4</b>
 *
 * <p>For any set of registered verticals, each contributing some surfaces and some default
 * overrides: {@code surfacesFor(v)} is the core surfaces followed by exactly {@code v}'s own, with
 * unique ids; other verticals' surfaces never leak; {@code defaultLayout} is the plugin override
 * when present, else the surface's core default; a plugin re-using a core id is rejected.
 */
class CardSurfaceRegistryProperties {

  record Contribution(String verticalId, List<String> ownSurfaceIds, Set<String> overriddenSurfaceIds, Set<String> hiddenCoreIds) {}

  @Property(tries = 100)
  void surfacesAreVerticalScopedAndDefaultsPreferOverrides(
      @ForAll("contributions") List<Contribution> contributions, @ForAll("asked") String askedVertical) {
    CardSurfaceRegistry registry = registry(contributions);
    Optional<Contribution> own =
        contributions.stream().filter(c -> c.verticalId().equals(askedVertical)).findFirst();

    List<CardSurfaceDefinition> surfaces = registry.surfacesFor(askedVertical);
    List<String> ids = surfaces.stream().map(CardSurfaceDefinition::surfaceId).toList();

    // core first (minus the vertical's hidden ones), in order
    Set<String> hidden = own.map(Contribution::hiddenCoreIds).orElse(Set.of());
    List<String> coreIds =
        CardLayoutDefaults.coreSurfaces().stream()
            .map(CardSurfaceDefinition::surfaceId)
            .filter(id -> !hidden.contains(id))
            .toList();
    assertThat(ids.subList(0, coreIds.size())).isEqualTo(coreIds);
    if (!hidden.isEmpty()) {
      assertThat(ids).doesNotContainAnyElementsOf(hidden);
    }
    // then exactly the vertical's own, in order
    assertThat(ids.subList(coreIds.size(), ids.size()))
        .isEqualTo(own.map(Contribution::ownSurfaceIds).orElse(List.of()));
    assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    // nothing from other verticals
    contributions.stream()
        .filter(c -> !c.verticalId().equals(askedVertical))
        .filter(c -> !c.ownSurfaceIds().isEmpty())
        .forEach(c -> assertThat(ids).doesNotContainAnyElementsOf(c.ownSurfaceIds()));

    // default precedence (Property 8)
    for (CardSurfaceDefinition s : surfaces) {
      for (CardVariant v : CardVariant.values()) {
        CardLayout layout = registry.defaultLayout(s, askedVertical, v);
        boolean overridden = own.map(c -> c.overriddenSurfaceIds().contains(s.surfaceId())).orElse(false);
        if (overridden) {
          assertThat(layout).isEqualTo(OVERRIDE);
        } else {
          assertThat(layout).isEqualTo(s.coreDefault(v));
        }
      }
    }
  }

  @Property(tries = 20)
  void pluginReusingACoreSurfaceIdIsRejected(@ForAll("asked") String vertical) {
    CardSurfaceRegistry registry =
        registry(List.of(new Contribution(vertical, List.of(CardSurfaceIds.PRODUCT_SEARCH), Set.of(), Set.of())));
    assertThatThrownBy(() -> registry.surfacesFor(vertical)).isInstanceOf(IllegalStateException.class);
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static final CardLayout OVERRIDE =
      CardLayout.of(CardLayoutDefaults.DEFAULT_OPTIONS, CardSection.of("o", CardRow.single("mrp")));

  @Provide
  Arbitrary<List<Contribution>> contributions() {
    Arbitrary<String> vertical = Arbitraries.of("medical", "grocery", "sports", "cafe");
    Arbitrary<List<String>> own =
        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6).map(s -> "own-" + s).list().uniqueElements().ofMaxSize(3);
    Arbitrary<Set<String>> overrides =
        Arbitraries.of(CardSurfaceIds.PRODUCT_SEARCH, CardSurfaceIds.SCAN_SELL, "own-x").set().ofMaxSize(3);
    Arbitrary<Set<String>> hidden =
        Arbitraries.of(CardSurfaceIds.PRODUCT_SEARCH, CardSurfaceIds.SCAN_SELL).set().ofMaxSize(2);
    return Combinators.combine(vertical, own, overrides, hidden)
        .as(Contribution::new)
        .list()
        .uniqueElements(Contribution::verticalId)
        .ofMaxSize(4)
        .map(
            list -> {
              // make own surface ids globally distinct per vertical to keep the scenario well-formed
              List<Contribution> out = new ArrayList<>();
              for (Contribution c : list) {
                List<String> ids = c.ownSurfaceIds().stream().map(id -> c.verticalId() + "-" + id).toList();
                Set<String> ov = new HashSet<>();
                for (String o : c.overriddenSurfaceIds()) {
                  ov.add(o.equals("own-x") && !ids.isEmpty() ? ids.get(0) : o);
                }
                out.add(new Contribution(c.verticalId(), ids, ov, c.hiddenCoreIds()));
              }
              return out;
            });
  }

  @Provide
  Arbitrary<String> asked() {
    return Arbitraries.of("medical", "grocery", "sports", "cafe", "unknown");
  }

  private static CardSurfaceRegistry registry(List<Contribution> contributions) {
    List<VerticalPlugin> plugins = new ArrayList<>();
    for (Contribution c : contributions) {
      plugins.add(plugin(c));
    }
    return new CardSurfaceRegistry(new PluginRegistry(plugins));
  }

  private static VerticalPlugin plugin(Contribution c) {
    CardSurfaceContributor contributor =
        new CardSurfaceContributor() {
          @Override
          public List<CardSurfaceDefinition> surfaces() {
            return c.ownSurfaceIds().stream()
                .map(id -> new CardSurfaceDefinition(id, id, false, Set.of(), Map.of(CardVariant.REGULAR, CardLayout.EMPTY)))
                .toList();
          }

          @Override
          public Optional<CardLayout> defaultLayout(String surfaceId, CardVariant variant) {
            return c.overriddenSurfaceIds().contains(surfaceId) ? Optional.of(OVERRIDE) : Optional.empty();
          }

          @Override
          public Set<String> hiddenCoreSurfaceIds() {
            return c.hiddenCoreIds();
          }
        };
    return new VerticalPlugin() {
      @Override
      public String getVerticalId() {
        return c.verticalId();
      }

      @Override
      public String getPluginVersion() {
        return "1.0.0";
      }

      @Override
      public Optional<CardSurfaceContributor> getCardSurfaceContributor() {
        return Optional.of(contributor);
      }
    };
  }
}
