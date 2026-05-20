package com.inventory.product.service;

import com.inventory.product.domain.model.PackagingUnitDefinition;
import com.inventory.product.domain.model.enums.SellUnitRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GST UQC master list (Customs / GST portal). Sell rules applied for pharmacy stock.
 */
public final class PackagingUnitCatalog {

  private static final Map<String, PackagingUnitDefinition> BY_UQC;

  static {
    List<PackagingUnitDefinition> all = new ArrayList<>();
    // S.No. 1–44 per GST UQC (ClearTax / Customs); sell hints for common pharmacy use.
    all.add(def("BAG", "BAGS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock counted in bags.", "Sell by bag."));
    all.add(def("BAL", "BALE", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock in bales.", "Sell by bale."));
    all.add(def("BDL", "BUNDLES", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock in bundles.", "Sell by bundle."));
    all.add(def("BKL", "BUCKLES", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock in buckles.", "Sell by buckle."));
    all.add(def("BOU", "BILLIONS OF UNITS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock in billions of units.", "Sell by unit."));
    all.add(def("BOX", "BOX", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "BOX", true,
        "Optional: units per box (e.g. 10 strips per box).", "Sell by box or base unit."));
    all.add(def("BTL", "BOTTLES", "MEASURE", SellUnitRule.PACK_ONLY, "BTL", true,
        "Liquid/cream in bottles; set ML per bottle.", "Sell full bottles only."));
    all.add(def("BUN", "BUNCHES", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Stock in bunches.", "Sell by bunch."));
    all.add(def("CAN", "CANS", "MEASURE", SellUnitRule.PACK_ONLY, "CAN", true,
        "Set volume per can.", "Sell full cans only."));
    all.add(def("CBM", "CUBIC METER", "VOLUME", SellUnitRule.PACK_ONLY, null, false,
        "Bulk volume.", "Sell by cubic meter."));
    all.add(def("CCM", "CUBIC CENTIMETER", "VOLUME", SellUnitRule.PACK_ONLY, "BTL", true,
        "Small volume packs.", "Sell sealed packs only."));
    all.add(def("CMS", "CENTIMETER", "LENGTH", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Length in cm.", "Sell by cm."));
    all.add(def("CTN", "CARTONS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "CTN", true,
        "Optional units per carton.", "Sell by carton."));
    all.add(def("DOZ", "DOZEN", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "DOZ", true,
        "12 pieces per dozen.", "Sell by dozen or piece."));
    all.add(def("DRM", "DRUM", "MEASURE", SellUnitRule.PACK_ONLY, "DRM", true,
        "Set volume per drum.", "Sell full drums only."));
    all.add(def("GGR", "GREAT GROSS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "144 dozen units.", "Sell by great gross."));
    all.add(def("GMS", "GRAMS", "WEIGHT", SellUnitRule.FRACTIONAL_BASE, "GMS", true,
        "Optional grams per pack.", "Sell by gram or pack."));
    all.add(def("GRS", "GROSS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "12 dozen.", "Sell by gross."));
    all.add(def("GYD", "GROSS YARDS", "LENGTH", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Gross yards.", "Sell by gross yard."));
    all.add(def("KGS", "KILOGRAMS", "WEIGHT", SellUnitRule.FRACTIONAL_BASE, "KGS", true,
        "Optional kg per pack.", "Sell by kg or pack."));
    all.add(def("KLR", "KILOLITER", "VOLUME", SellUnitRule.PACK_ONLY, "BTL", true,
        "Set ML/L per container.", "Sell full containers only."));
    all.add(def("KME", "KILOMETRE", "LENGTH", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Kilometres.", "Sell by km."));
    all.add(def("MLT", "MILLILITRE", "VOLUME", SellUnitRule.PACK_ONLY, "BTL", true,
        "e.g. 100 ML per bottle. Stock in ML.", "Sell full bottles only (not loose ML)."));
    all.add(def("MTR", "METERS", "LENGTH", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Meters.", "Sell by meter."));
    all.add(def("MTS", "METRIC TONS", "WEIGHT", SellUnitRule.PACK_ONLY, null, false,
        "Metric tons.", "Sell by ton."));
    all.add(def("NOS", "NUMBERS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "Optional units per pack.", "Sell by number or pack."));
    all.add(def("PAC", "PACKS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "Optional items per pack.", "Sell by pack or base unit."));
    all.add(def("PCS", "PIECES", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "Optional pieces per pack.", "Sell by piece or pack."));
    all.add(def("PRS", "PAIRS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Pairs.", "Sell by pair."));
    all.add(def("QTL", "QUINTAL", "WEIGHT", SellUnitRule.PACK_ONLY, null, false,
        "Quintal (100 kg).", "Sell by quintal."));
    all.add(def("ROL", "ROLLS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Rolls.", "Sell by roll."));
    all.add(def("SET", "SETS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Sets.", "Sell by set."));
    all.add(def("SQF", "SQUARE FEET", "AREA", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Square feet.", "Sell by sq ft."));
    all.add(def("SQM", "SQUARE METERS", "AREA", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Square meters.", "Sell by sq m."));
    all.add(def("SQY", "SQUARE YARDS", "AREA", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Square yards.", "Sell by sq yd."));
    all.add(def("TBS", "TABLETS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "e.g. 50 tablets per strip. Stock in tablets.", "Sell by tablet or strip."));
    all.add(def("TGM", "TEN GROSS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Ten gross.", "Sell by ten gross."));
    all.add(def("THD", "THOUSANDS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Thousands.", "Sell by thousand."));
    all.add(def("TON", "TONNES", "WEIGHT", SellUnitRule.PACK_ONLY, null, false,
        "Tonnes.", "Sell by tonne."));
    all.add(def("TUB", "TUBES", "MEASURE", SellUnitRule.PACK_ONLY, "TUB", true,
        "e.g. grams/ML per tube.", "Sell full tubes only."));
    all.add(def("UGS", "US GALLONS", "VOLUME", SellUnitRule.PACK_ONLY, "BTL", true,
        "US gallons per container.", "Sell full containers only."));
    all.add(def("UNT", "UNITS", "MEASURE", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "Generic unit.", "Sell by unit or pack."));
    all.add(def("YDS", "YARDS", "LENGTH", SellUnitRule.FRACTIONAL_BASE, null, false,
        "Yards.", "Sell by yard."));
    all.add(def("LTR", "LITRES", "VOLUME", SellUnitRule.PACK_ONLY, "BTL", true,
        "Litres per bottle.", "Sell full bottles only."));
    all.add(def("OTH", "OTHERS", "OTHER", SellUnitRule.FRACTIONAL_BASE, "PAC", true,
        "Use when no other UQC fits (GST OTH).", "Sell by unit or pack."));

    Map<String, PackagingUnitDefinition> map = new LinkedHashMap<>();
    for (PackagingUnitDefinition d : all) {
      map.put(d.getUqc(), d);
    }
    BY_UQC = Collections.unmodifiableMap(map);
  }

  private PackagingUnitCatalog() {
  }

  private static PackagingUnitDefinition def(
      String uqc,
      String label,
      String category,
      SellUnitRule sellUnitRule,
      String defaultPackUqc,
      boolean allowsUnitsPerPack,
      String registrationHint,
      String sellHint) {
    return new PackagingUnitDefinition(
        uqc, label, category, sellUnitRule, defaultPackUqc,
        allowsUnitsPerPack, registrationHint, sellHint);
  }

  public static List<PackagingUnitDefinition> all() {
    return List.copyOf(BY_UQC.values());
  }

  public static Optional<PackagingUnitDefinition> find(String uqc) {
    if (uqc == null || uqc.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_UQC.get(uqc.trim().toUpperCase()));
  }

  public static PackagingUnitDefinition require(String uqc) {
    return find(uqc).orElseThrow(() -> new IllegalArgumentException("Unknown UQC: " + uqc));
  }

  public static boolean isKnownUqc(String uqc) {
    return find(uqc).isPresent();
  }
}
