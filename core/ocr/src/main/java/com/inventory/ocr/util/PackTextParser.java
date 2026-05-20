package com.inventory.ocr.util;

import com.inventory.ocr.dto.ParsedInventoryItem;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses invoice Pack column values (e.g. 1*10, 1*15TAB, 15*15, 1*100ML) into base UQC + units per pack.
 */
public final class PackTextParser {

  private static final Pattern PACK_TIMES = Pattern.compile(
      "(?i)^\\s*(\\d+)\\s*[*x×X]\\s*(\\d+)\\s*([A-Za-z]{1,6})?\\s*$");
  private static final Pattern PACK_SINGLE = Pattern.compile("(?i)^\\s*(\\d+)\\s*([A-Za-z]{1,6})?\\s*$");

  private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
      Map.entry("TAB", "TBS"),
      Map.entry("TABS", "TBS"),
      Map.entry("TABLET", "TBS"),
      Map.entry("TABLETS", "TBS"),
      Map.entry("TBS", "TBS"),
      Map.entry("CAP", "PCS"),
      Map.entry("CAPS", "PCS"),
      Map.entry("CAPSULE", "PCS"),
      Map.entry("CAPSULES", "PCS"),
      Map.entry("PCS", "PCS"),
      Map.entry("PC", "PCS"),
      Map.entry("ML", "MLT"),
      Map.entry("MLT", "MLT"),
      Map.entry("MILLILITRE", "MLT"),
      Map.entry("MILLILITER", "MLT"),
      Map.entry("LTR", "KLR"),
      Map.entry("L", "KLR"),
      Map.entry("GM", "GMS"),
      Map.entry("GMS", "GMS"),
      Map.entry("GRAM", "GMS"),
      Map.entry("GRAMS", "GMS"),
      Map.entry("KG", "KGS"),
      Map.entry("KGS", "KGS"),
      Map.entry("BTL", "BTL"),
      Map.entry("BOTTLE", "BTL"),
      Map.entry("BOT", "BTL"),
      Map.entry("NOS", "NOS"),
      Map.entry("UNT", "NOS"),
      Map.entry("UNIT", "NOS"),
      Map.entry("PAC", "PAC"),
      Map.entry("PACK", "PAC"),
      Map.entry("STRIP", "PAC")
  );

  private static final Pattern NAME_UNIT_SUFFIX = Pattern.compile(
      "(?i)(?:\\b|\\*|/)(TAB|TABLETS?|CAPS?(?:ULES)?|MLT?|GMS?|GRAMS?|KGS?|BTL|BOTTLES?|SPRAY|NS|INJ)\\s*$");

  private PackTextParser() {}

  public record ParsedPack(Integer unitsPerPack, String baseUnitUqc, String rawPack) {}

  /** Remove leading pack token wrongly merged into product name (e.g. 1*10TABPULMICUS → PULMICUS). */
  public static String cleanProductName(String name) {
    if (name == null || name.isBlank()) {
      return name;
    }
    Matcher m = Pattern.compile("(?i)^\\d+\\s*[*x×X]\\s*\\d+[A-Za-z]*\\s*(.+)$").matcher(name.trim());
    if (m.matches()) {
      return m.group(1).trim();
    }
    return name.trim();
  }

  public static void applyPackaging(ParsedInventoryItem item, String packRaw, String productName) {
    if (item == null) {
      return;
    }
    ParsedPack parsed = parse(packRaw, productName);
    if (parsed == null) {
      return;
    }
    if (parsed.rawPack() != null) {
      item.setPackDetail(parsed.rawPack());
    }
    if (parsed.baseUnitUqc() != null) {
      item.setBaseUnit(parsed.baseUnitUqc());
    }
    if (parsed.unitsPerPack() != null && parsed.unitsPerPack() > 1) {
      item.setUnitsPerPack(parsed.unitsPerPack());
    }
  }

  public static ParsedPack parse(String packRaw, String productName) {
    String raw = packRaw != null ? packRaw.trim() : "";
    if (raw.isEmpty()) {
      String fromName = inferBaseUnitFromName(productName);
      return fromName != null ? new ParsedPack(null, fromName, null) : null;
    }

    Matcher times = PACK_TIMES.matcher(raw);
    if (times.matches()) {
      int left = Integer.parseInt(times.group(1));
      int right = Integer.parseInt(times.group(2));
      String unitToken = times.group(3);
      int unitsPerPack = left <= 1 ? right : left * right;
      String base = normalizeUnitToken(unitToken);
      if (base == null) {
        base = inferBaseUnitFromName(productName);
      }
      return new ParsedPack(unitsPerPack > 1 ? unitsPerPack : null, base, raw);
    }

    Matcher single = PACK_SINGLE.matcher(raw);
    if (single.matches()) {
      int n = Integer.parseInt(single.group(1));
      String base = normalizeUnitToken(single.group(2));
      if (base == null) {
        base = inferBaseUnitFromName(productName);
      }
      Integer upp = n > 1 ? n : null;
      return new ParsedPack(upp, base, raw);
    }

    String fromName = inferBaseUnitFromName(productName);
    if (fromName != null) {
      return new ParsedPack(null, fromName, raw);
    }
    return new ParsedPack(null, null, raw);
  }

  /** Map pack/name token to GST UQC code (e.g. TAB → TBS). */
  public static String resolveBaseUnitUqc(String token) {
    return normalizeUnitToken(token);
  }

  private static String normalizeUnitToken(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String key = token.trim().toUpperCase(Locale.ROOT);
    return UNIT_ALIASES.get(key);
  }

  static String inferBaseUnitFromName(String productName) {
    if (productName == null || productName.isBlank()) {
      return null;
    }
    String upper = productName.toUpperCase(Locale.ROOT);
    Matcher m = NAME_UNIT_SUFFIX.matcher(upper);
    if (m.find()) {
      return normalizeUnitToken(m.group(1));
    }
    if (upper.contains(" TAB") || upper.endsWith(" TAB") || upper.contains("TAB ")) {
      return "TBS";
    }
    if (upper.contains(" CAP") || upper.contains("CAPS")) {
      return "PCS";
    }
    if (upper.contains(" ML") || upper.contains("SYRUP") || upper.contains("SUSP")) {
      return "MLT";
    }
    if (upper.contains("SPRAY")) {
      return "NOS";
    }
    if (upper.contains("INJ") || upper.contains("INJECTION")) {
      return "NOS";
    }
    return null;
  }
}
