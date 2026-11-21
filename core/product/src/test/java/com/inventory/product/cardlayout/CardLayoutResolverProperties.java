package com.inventory.product.cardlayout;

// Feature: configurable-product-card, Property 7: Resolution is a filter

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 7: Resolution is a filter.
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 5.3</b>
 *
 * <p>For any layout (including unknown, label-only and excluded keys) and surface: resolved keys are
 * the input keys restricted to CARD-usable, non-excluded keys, in order; no resolved row or section
 * is empty; options are unchanged; every resolved field carries its catalog enrichment with the
 * override label winning; resolving never throws.
 */
class CardLayoutResolverProperties {

  private static final FieldCatalog CATALOG = CardLayoutTestFixtures.staticCatalog();
  private final CardLayoutResolver resolver = new CardLayoutResolver();

  @Property(tries = 150)
  void resolutionFiltersEnrichesAndPrunes(
      @ForAll("layouts") CardLayout layout, @ForAll("surfaces") CardSurfaceDefinition surface) {
    ResolvedCardLayout resolved = resolver.resolve(layout, surface, CATALOG);

    List<String> expected =
        CardLayoutTestFixtures.keys(layout).stream()
            .filter(k -> CATALOG.findForUsage(k, FieldUsage.CARD).isPresent())
            .filter(k -> !surface.excludes(k))
            .toList();
    assertThat(CardLayoutTestFixtures.keys(resolved)).isEqualTo(expected);

    resolved.sections().forEach(s -> {
      assertThat(s.rows()).isNotEmpty();
      s.rows().forEach(r -> assertThat(r.fields()).isNotEmpty());
    });
    assertThat(resolved.options()).isEqualTo(layout.options());

    // enrichment — walk input and output in lock-step so repeated keys line up by position
    List<ResolvedCardLayout.Field> out =
        resolved.sections().stream().flatMap(rs -> rs.rows().stream()).flatMap(rr -> rr.fields().stream()).toList();
    int i = 0;
    for (var s : layout.sections()) {
      for (var r : s.rows()) {
        for (var f : r.fields()) {
          var cf = CATALOG.findForUsage(f.fieldKey(), FieldUsage.CARD);
          if (cf.isEmpty() || surface.excludes(f.fieldKey())) {
            continue;
          }
          ResolvedCardLayout.Field rf = out.get(i++);
          assertThat(rf.fieldKey()).isEqualTo(f.fieldKey());
          assertThat(rf.label()).isEqualTo(f.labelOverride() != null ? f.labelOverride() : cf.get().label());
          assertThat(rf.valueType()).isEqualTo(cf.get().valueType());
          assertThat(rf.sourceGroup()).isEqualTo(cf.get().sourceGroup());
          assertThat(rf.itemPath()).isEqualTo(cf.get().itemPath());
          assertThat(rf.sensitivity()).isEqualTo(cf.get().sensitivity());
          assertThat(rf.showLabel()).isEqualTo(f.showLabel());
          assertThat(rf.emphasis()).isEqualTo(f.emphasis());
        }
      }
    }
    assertThat(i).isEqualTo(out.size());
  }

  @Property(tries = 50)
  void sectionAndRowOrderIsPreserved(@ForAll("layouts") CardLayout layout) {
    CardSurfaceDefinition surface = CardLayoutTestFixtures.surface("s", true, java.util.Set.of());
    ResolvedCardLayout resolved = resolver.resolve(layout, surface, CATALOG);

    // section ids in resolved output are a subsequence of input section ids
    List<String> inputIds = layout.sections().stream().map(s -> s.id()).toList();
    List<String> outputIds = resolved.sections().stream().map(s -> s.id()).toList();
    int i = 0;
    for (String id : inputIds) {
      if (i < outputIds.size() && outputIds.get(i).equals(id)) {
        i++;
      }
    }
    assertThat(i).isEqualTo(outputIds.size());
  }

  @Provide
  Arbitrary<CardLayout> layouts() {
    return CardLayoutTestFixtures.layouts(CATALOG, true);
  }

  @Provide
  Arbitrary<CardSurfaceDefinition> surfaces() {
    return CardLayoutTestFixtures.surfaces(CATALOG);
  }
}
