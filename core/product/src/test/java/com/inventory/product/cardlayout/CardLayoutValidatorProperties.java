package com.inventory.product.cardlayout;

// Feature: configurable-product-card, Property 3: Validator totality

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 3: Validator totality.
 *
 * <p><b>Validates: Requirements 3.3, 3.4, 3.5, 3.6, 3.7</b>
 *
 * <p>For any request built from a well-formed layout the validator returns exactly the surface's
 * variants, every cap holds and there are no duplicate keys; for any request with an injected
 * violation it throws one {@link ValidationException} whose message names that violation.
 */
class CardLayoutValidatorProperties {

  private static final FieldCatalog CATALOG = CardLayoutTestFixtures.staticCatalog();
  private final CardLayoutValidator validator = new CardLayoutValidator();
  private final Function<CardVariant, CardLayout> defaults = v -> CardLayoutDefaults.productSearch();

  @Property(tries = 150)
  void wellFormedRequestsPassAndAreFilled(
      @ForAll("cleanLayouts") CardLayout layout, @ForAll("awareness") boolean aware) {
    CardSurfaceDefinition surface = CardLayoutTestFixtures.surface("s", aware, Set.of());
    SaveCardLayoutRequest req = CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, layout));

    Map<CardVariant, CardLayout> result = validator.validate(req, surface, CATALOG, defaults);

    assertThat(result.keySet()).isEqualTo(surface.variants());
    CardLayout regular = result.get(CardVariant.REGULAR);
    assertThat(CardLayoutTestFixtures.keys(regular)).isEqualTo(CardLayoutTestFixtures.keys(layout));
    assertThat(regular.options()).isEqualTo(layout.options());
    assertThat(regular.sections().size()).isLessThanOrEqualTo(CardLayoutDefaults.MAX_SECTIONS);
    assertThat(regular.fieldCount()).isLessThanOrEqualTo(CardLayoutDefaults.MAX_FIELDS_TOTAL);
    List<String> keys = CardLayoutTestFixtures.keys(regular);
    assertThat(new HashSet<>(keys)).hasSameSizeAs(keys);
    if (aware) {
      assertThat(result.get(CardVariant.BASIC)).isEqualTo(CardLayoutDefaults.productSearch());
    }
  }

  @Property(tries = 150)
  void injectedViolationsAreNamed(
      @ForAll("cleanLayouts") CardLayout layout, @ForAll("violations") Violation violation) {
    CardSurfaceDefinition surface = CardLayoutTestFixtures.surface("s", true, Set.of("description"));
    SaveCardLayoutRequest req = violation.inject(layout, surface);

    assertThatThrownBy(() -> validator.validate(req, surface, CATALOG, defaults))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(violation.expectedFragment());
  }

  // ---- generators ----------------------------------------------------------------------------

  /** Layouts that satisfy every rule: unique CARD keys, caps honoured, short texts. */
  @Provide
  Arbitrary<CardLayout> cleanLayouts() {
    List<String> pool = CardLayoutTestFixtures.cardKeys(CATALOG);
    return Arbitraries.of(pool)
        .list()
        .uniqueElements()
        .ofMaxSize(CardLayoutDefaults.MAX_FIELDS_TOTAL)
        .flatMap(
            keys ->
                Arbitraries.integers()
                    .between(1, CardLayoutDefaults.MAX_SECTIONS)
                    .map(sectionCount -> distribute(keys, sectionCount)));
  }

  @Provide
  Arbitrary<Boolean> awareness() {
    return Arbitraries.of(true, false);
  }

  @Provide
  Arbitrary<Violation> violations() {
    return Arbitraries.of(Violation.values());
  }

  /** Spreads keys over sections/rows while honouring row and section caps. */
  private static CardLayout distribute(List<String> keys, int sectionCount) {
    List<CardSection> sections = new ArrayList<>();
    int perSection = Math.max(1, (int) Math.ceil(keys.size() / (double) sectionCount));
    int idx = 0;
    int sid = 0;
    while (idx < keys.size()) {
      List<CardRow> rows = new ArrayList<>();
      int sectionEnd = Math.min(keys.size(), idx + perSection);
      while (idx < sectionEnd && rows.size() < CardLayoutDefaults.MAX_ROWS_PER_SECTION) {
        List<CardField> fields = new ArrayList<>();
        int rowEnd = Math.min(sectionEnd, idx + CardLayoutDefaults.MAX_FIELDS_PER_ROW);
        for (; idx < rowEnd; idx++) {
          fields.add(new CardField(keys.get(idx), idx % 2 == 0, null, Emphasis.values()[idx % 3]));
        }
        rows.add(new CardRow(fields));
      }
      sections.add(new CardSection("s" + (sid++), null, sid % 2 == 0, rows));
      if (sections.size() == CardLayoutDefaults.MAX_SECTIONS && idx < keys.size()) {
        // put the remainder on the last section's rows if caps allow; otherwise stop (rare)
        break;
      }
    }
    return new CardLayout(sections, CardLayoutDefaults.DEFAULT_OPTIONS);
  }

  // ---- violation catalogue -------------------------------------------------------------------

  enum Violation {
    UNKNOWN_KEY("unknown fields: nope") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return withExtraRow(l, new CardField("nope", true, null, Emphasis.NORMAL));
      }
    },
    LABEL_ONLY_KEY("fields not available on cards: shopName") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return withExtraRow(l, new CardField("shopName", true, null, Emphasis.NORMAL));
      }
    },
    EXCLUDED_KEY("field description is excluded on surface s") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return withExtraRow(withoutKey(l, "description"), new CardField("description", true, null, Emphasis.NORMAL));
      }
    },
    DUPLICATE_KEY("duplicate fields: mrp") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        CardLayout base = withoutKey(l, "mrp");
        CardLayout once = appendRow(base, new CardField("mrp", true, null, Emphasis.NORMAL));
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, appendRow(once, new CardField("mrp", true, null, Emphasis.NORMAL))));
      }
    },
    TOO_MANY_SECTIONS("at most 6 sections allowed") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        List<CardSection> sections = new ArrayList<>();
        List<String> keys = CardLayoutTestFixtures.cardKeys(CATALOG);
        for (int i = 0; i < 7; i++) {
          sections.add(CardSection.of("x" + i, CardRow.single(keys.get(i))));
        }
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, new CardLayout(sections, CardLayoutDefaults.DEFAULT_OPTIONS)));
      }
    },
    TOO_MANY_ROWS("has 9 rows (max 8)") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        List<CardRow> rows = new ArrayList<>();
        List<String> keys = CardLayoutTestFixtures.cardKeys(CATALOG);
        for (int i = 0; i < 9; i++) {
          rows.add(CardRow.single(keys.get(i)));
        }
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, CardLayout.of(CardLayoutDefaults.DEFAULT_OPTIONS, new CardSection("big", null, false, rows))));
      }
    },
    TOO_MANY_FIELDS_IN_ROW("has 4 fields (max 3)") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        List<String> keys = CardLayoutTestFixtures.cardKeys(CATALOG);
        CardRow row =
            CardRow.of(CardField.of(keys.get(0)), CardField.of(keys.get(1)), CardField.of(keys.get(2)), CardField.of(keys.get(3)));
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, CardLayout.of(CardLayoutDefaults.DEFAULT_OPTIONS, CardSection.of("wide", row))));
      }
    },
    EMPTY_ROW("has no fields") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, CardLayout.of(CardLayoutDefaults.DEFAULT_OPTIONS, CardSection.of("e", new CardRow(List.of())))));
      }
    },
    TOO_MANY_FIELDS_TOTAL("at most 20 fields allowed; 21 enabled") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        List<String> keys = CardLayoutTestFixtures.cardKeys(CATALOG);
        List<CardSection> sections = new ArrayList<>();
        int idx = 0;
        for (int si = 0; si < 3 && idx < 21; si++) {
          List<CardRow> rows = new ArrayList<>();
          for (int ri = 0; ri < 7 && idx < 21; ri++) {
            rows.add(CardRow.single(keys.get(idx++)));
          }
          sections.add(new CardSection("t" + si, null, false, rows));
        }
        return CardLayoutTestFixtures.request(
            Map.of(CardVariant.REGULAR, new CardLayout(sections, CardLayoutDefaults.DEFAULT_OPTIONS)));
      }
    },
    DUPLICATE_SECTION_ID("section ids must be unique and non-blank") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return CardLayoutTestFixtures.request(
            Map.of(
                CardVariant.REGULAR,
                CardLayout.of(
                    CardLayoutDefaults.DEFAULT_OPTIONS,
                    CardSection.of("dup", CardRow.single("mrp")),
                    CardSection.of("dup", CardRow.single("companyName")))));
      }
    },
    LONG_TITLE("title exceeds 40 characters") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return CardLayoutTestFixtures.request(
            Map.of(
                CardVariant.REGULAR,
                CardLayout.of(
                    CardLayoutDefaults.DEFAULT_OPTIONS,
                    new CardSection("t", "x".repeat(41), false, List.of(CardRow.single("mrp"))))));
      }
    },
    LONG_LABEL_OVERRIDE("label override for mrp exceeds 40 characters") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        return CardLayoutTestFixtures.request(
            Map.of(
                CardVariant.REGULAR,
                CardLayout.of(
                    CardLayoutDefaults.DEFAULT_OPTIONS,
                    CardSection.of("t", CardRow.of(new CardField("mrp", true, "y".repeat(41), Emphasis.NORMAL))))));
      }
    },
    BAD_EMPHASIS(CardLayoutValidator.EMPHASIS_ERROR) {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        SaveCardLayoutRequest.FieldSpec f = new SaveCardLayoutRequest.FieldSpec("mrp", null, null, "LOUD");
        return single(f, null);
      }
    },
    BAD_BLANK_BEHAVIOUR(CardLayoutValidator.BLANK_BEHAVIOUR_ERROR) {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        SaveCardLayoutRequest.FieldSpec f = new SaveCardLayoutRequest.FieldSpec("mrp", null, null, null);
        return single(f, new SaveCardLayoutRequest.OptionsSpec("SOMETIMES", null, null));
      }
    },
    BAD_SHOW_LABEL("showLabel for mrp must be true or false") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        SaveCardLayoutRequest.FieldSpec f =
            new SaveCardLayoutRequest.FieldSpec("mrp", LenientBoolean.invalid("yes"), null, null);
        return single(f, null);
      }
    },
    BAD_SHOW_CHIPS("showAttributeChips must be true or false") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        SaveCardLayoutRequest.FieldSpec f = new SaveCardLayoutRequest.FieldSpec("mrp", null, null, null);
        return single(f, new SaveCardLayoutRequest.OptionsSpec(null, LenientBoolean.invalid("1"), null));
      }
    },
    UNKNOWN_VARIANT("Unknown variant: PREMIUM") {
      SaveCardLayoutRequest inject(CardLayout l, CardSurfaceDefinition s) {
        Map<String, SaveCardLayoutRequest.LayoutSpec> m = new LinkedHashMap<>();
        m.put("PREMIUM", CardLayoutTestFixtures.spec(l));
        return new SaveCardLayoutRequest(m);
      }
    };

    private final String fragment;

    Violation(String fragment) {
      this.fragment = fragment;
    }

    String expectedFragment() {
      return fragment;
    }

    abstract SaveCardLayoutRequest inject(CardLayout layout, CardSurfaceDefinition surface);

    static SaveCardLayoutRequest withExtraRow(CardLayout l, CardField extra) {
      return CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, appendRow(l, extra)));
    }

    static CardLayout appendRow(CardLayout l, CardField extra) {
      List<CardSection> sections = new ArrayList<>(l.sections());
      sections.add(CardSection.of("extra-" + sections.size() + "-" + extra.fieldKey().hashCode(), CardRow.of(extra)));
      return new CardLayout(sections, l.options());
    }

    static CardLayout withoutKey(CardLayout l, String key) {
      List<CardSection> sections = new ArrayList<>();
      for (CardSection s : l.sections()) {
        List<CardRow> rows = new ArrayList<>();
        for (CardRow r : s.rows()) {
          List<CardField> fields = r.fields().stream().filter(f -> !f.fieldKey().equals(key)).toList();
          if (!fields.isEmpty()) {
            rows.add(new CardRow(fields));
          }
        }
        if (!rows.isEmpty()) {
          sections.add(new CardSection(s.id(), s.title(), s.dividerAbove(), rows));
        }
      }
      return new CardLayout(sections, l.options());
    }

    static SaveCardLayoutRequest single(SaveCardLayoutRequest.FieldSpec f, SaveCardLayoutRequest.OptionsSpec o) {
      SaveCardLayoutRequest.RowSpec row = new SaveCardLayoutRequest.RowSpec(List.of(f));
      SaveCardLayoutRequest.SectionSpec section = new SaveCardLayoutRequest.SectionSpec("only", null, null, List.of(row));
      return new SaveCardLayoutRequest(Map.of("REGULAR", new SaveCardLayoutRequest.LayoutSpec(List.of(section), o)));
    }
  }

  @SuppressWarnings("unused")
  private static boolean isCardKey(String k) {
    return CATALOG.findForUsage(k, FieldUsage.CARD).isPresent();
  }
}
