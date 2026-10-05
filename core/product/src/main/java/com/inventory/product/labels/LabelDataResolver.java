package com.inventory.product.labels;

import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Location;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.SchemeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the {@code values} map of a Label_Data entry: exactly one formatted string per enabled
 * printable field of the effective layout, {@code ""} wherever the source is missing.
 *
 * <p>Pure with respect to its inputs — all documents (product, lot, pricing, pool row, extension
 * row) are loaded by the caller once per request and handed in through {@link LabelTarget} and
 * {@link ResolutionContext}. Every raw value is passed through {@link LabelValueFormatter} using
 * the catalog's value type for the field.
 */
@Component
@Slf4j
public class LabelDataResolver {

  /**
   * One (code, product) pair to resolve. Every reference other than {@code code} may be null:
   *
   * <ul>
   *   <li>{@code product == null && poolRow != null} → pool-only code (Req 6.8)
   *   <li>{@code product == null && poolRow == null} → unknown code (Req 6.9)
   *   <li>{@code lot == null} → no inventory lot; lot and pricing fields are {@code ""} (Req 6.13)
   *   <li>{@code pricing == null} → pricing fields are {@code ""} (Req 6.13)
   *   <li>{@code extensionRow == null} → no vertical extension data for the lot
   * </ul>
   */
  public record LabelTarget(
      String code,
      Product product,
      Inventory lot,
      Pricing pricing,
      Map<String, Object> extensionRow,
      BarcodePool poolRow) {}

  /** Per-request inputs shared by every target: the shop, its catalog and the effective layout. */
  public record ResolutionContext(Shop shop, FieldCatalog catalog, EffectiveLayout layout) {}

  private final LabelExtensionReader extensionReader;

  public LabelDataResolver(LabelExtensionReader extensionReader) {
    this.extensionReader = extensionReader;
  }

  /**
   * Resolves one string per enabled field, in layout order.
   *
   * @return a {@link LinkedHashMap} whose key set equals {@code ctx.layout().enabledFields()} keys
   *     (in order); never contains null values
   */
  public Map<String, String> resolve(LabelTarget target, ResolutionContext ctx) {
    Map<String, String> values = new LinkedHashMap<>();
    if (ctx == null || ctx.layout() == null) {
      return values;
    }
    FieldCatalog catalog = ctx.catalog();
    Product product = target == null ? null : target.product();
    Inventory lot = target == null ? null : target.lot();
    // Currency fields (including pool-only MRP) render with the effective layout's style (Req 11).
    CurrencyStyle currencyStyle = ctx.layout().currencyStyle();

    // Merged lot vertical data (core lot props + extension row), keyed by apiKey. Computed lazily
    // only when some enabled field needs it.
    Map<String, Object> merged = null;
    boolean mergedComputed = false;

    for (EnabledFieldDto enabled : ctx.layout().enabledFields()) {
      String fieldKey = enabled.fieldKey();
      if (fieldKey == null || values.containsKey(fieldKey)) {
        continue;
      }
      PrintableField field =
          catalog == null ? null : catalog.findForUsage(fieldKey, FieldUsage.LABEL).orElse(null);
      ValueType valueType = field != null ? field.valueType() : parseValueType(enabled.valueType());

      Object raw;
      try {
        if (needsMergedLot(fieldKey, field) && !mergedComputed) {
          merged = mergedLotFields(target, ctx);
          mergedComputed = true;
        }
        raw = rawValue(fieldKey, field, target, ctx, product, lot, merged);
      } catch (RuntimeException e) {
        log.warn(
            "Could not resolve label field {} for code {}: {}",
            fieldKey,
            target == null ? null : target.code(),
            e.getMessage());
        raw = null;
      }
      String formatted = LabelValueFormatter.format(raw, valueType, currencyStyle);
      values.put(fieldKey, formatted == null ? "" : formatted);
    }
    return values;
  }

  // ---------------------------------------------------------------------------------------------
  // dispatch
  // ---------------------------------------------------------------------------------------------

  private static boolean needsMergedLot(String fieldKey, PrintableField field) {
    if (LabelFieldKeys.BATCH_NO.equals(fieldKey)
        || LabelFieldKeys.EXPIRY_DATE.equals(fieldKey)
        || LabelFieldKeys.RECEIVED_DATE.equals(fieldKey)) {
      return true;
    }
    if (field != null && field.sourceGroup() == SourceGroup.LOT) {
      return true;
    }
    return LabelFieldKeys.isVerticalKey(fieldKey);
  }

  private Map<String, Object> mergedLotFields(LabelTarget target, ResolutionContext ctx) {
    if (target == null || target.lot() == null || ctx.catalog() == null) {
      return Map.of();
    }
    try {
      Map<String, Object> merged =
          extensionReader.mergeVerticalFields(target.lot(), target.extensionRow(), ctx.catalog());
      return merged == null ? Map.of() : merged;
    } catch (RuntimeException e) {
      log.warn(
          "Could not merge vertical fields for lot {} (code {}): {}",
          target.lot().getId(),
          target.code(),
          e.getMessage());
      return Map.of();
    }
  }

  private Object rawValue(
      String fieldKey,
      PrintableField field,
      LabelTarget target,
      ResolutionContext ctx,
      Product product,
      Inventory lot,
      Map<String, Object> merged) {
    // barcodeText is always the code, whatever the target kind.
    if (LabelFieldKeys.BARCODE_TEXT.equals(fieldKey)) {
      return target == null ? null : target.code();
    }
    if (LabelFieldKeys.isPricingRateKey(fieldKey)) {
      return rateValue(target == null ? null : target.pricing(), fieldKey);
    }
    if (LabelFieldKeys.isVerticalKey(fieldKey)) {
      return verticalValue(field, fieldKey, product, merged, ctx.catalog());
    }

    SourceGroup group = field != null ? field.sourceGroup() : inferGroup(fieldKey);
    if (group == null) {
      return null;
    }
    return switch (group) {
      case PRODUCT -> productValue(fieldKey, product, target == null ? null : target.poolRow());
      case PRICING ->
          pricingValue(
              fieldKey, target == null ? null : target.pricing(), product, target == null ? null : target.poolRow());
      case LOT -> lotValue(fieldKey, field, lot, merged);
      case SHOP -> shopValue(fieldKey, ctx.shop());
      case VERTICAL -> verticalValue(field, fieldKey, product, merged, ctx.catalog());
    };
  }

  /** Best-effort group when the field is missing from the catalog (defensive; should not happen). */
  private static SourceGroup inferGroup(String fieldKey) {
    return switch (fieldKey) {
      case LabelFieldKeys.PRODUCT_NAME,
          LabelFieldKeys.COMPANY_NAME,
          LabelFieldKeys.HSN,
          LabelFieldKeys.BASE_UNIT,
          LabelFieldKeys.PACK_SIZE,
          LabelFieldKeys.DESCRIPTION -> SourceGroup.PRODUCT;
      case LabelFieldKeys.MRP,
          LabelFieldKeys.SELLING_PRICE,
          LabelFieldKeys.PTR,
          LabelFieldKeys.COST_PRICE,
          LabelFieldKeys.SALE_SCHEME,
          LabelFieldKeys.GST_RATE -> SourceGroup.PRICING;
      case LabelFieldKeys.BATCH_NO, LabelFieldKeys.EXPIRY_DATE, LabelFieldKeys.RECEIVED_DATE ->
          SourceGroup.LOT;
      case LabelFieldKeys.SHOP_NAME,
          LabelFieldKeys.SHOP_TAGLINE,
          LabelFieldKeys.SHOP_PHONE,
          LabelFieldKeys.SHOP_EMAIL,
          LabelFieldKeys.SHOP_ADDRESS,
          LabelFieldKeys.SHOP_GSTIN,
          LabelFieldKeys.SHOP_FSSAI,
          LabelFieldKeys.SHOP_DL_NO -> SourceGroup.SHOP;
      default -> null;
    };
  }

  private static ValueType parseValueType(String wireName) {
    if (wireName == null) {
      return ValueType.TEXT;
    }
    for (ValueType type : ValueType.values()) {
      if (type.wireName().equalsIgnoreCase(wireName) || type.name().equalsIgnoreCase(wireName)) {
        return type;
      }
    }
    return ValueType.TEXT;
  }

  // ---------------------------------------------------------------------------------------------
  // product group (Req 6.2, 6.8)
  // ---------------------------------------------------------------------------------------------

  private static Object productValue(String fieldKey, Product product, BarcodePool poolRow) {
    if (product == null) {
      // Pool-only code: only the stored label name / company are known (Req 6.8).
      if (poolRow == null) {
        return null;
      }
      return switch (fieldKey) {
        case LabelFieldKeys.PRODUCT_NAME -> poolRow.getLabelName();
        case LabelFieldKeys.COMPANY_NAME -> poolRow.getLabelCompany();
        default -> null;
      };
    }
    return switch (fieldKey) {
      case LabelFieldKeys.PRODUCT_NAME -> product.getName();
      case LabelFieldKeys.COMPANY_NAME -> product.getCompanyName();
      case LabelFieldKeys.HSN -> product.getHsn();
      case LabelFieldKeys.BASE_UNIT -> product.getBaseUnit();
      case LabelFieldKeys.DESCRIPTION -> product.getDescription();
      case LabelFieldKeys.PACK_SIZE -> packSize(product.getUnitConversions(), product.getBaseUnit());
      default -> null;
    };
  }

  /** {@code "{factor} {baseUnit} / {unit}"}; null when there is no usable conversion. */
  static String packSize(UnitConversion conversion, String baseUnit) {
    if (conversion == null || conversion.getFactor() == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append(conversion.getFactor());
    if (StringUtils.hasText(baseUnit)) {
      sb.append(' ').append(baseUnit.trim());
    }
    if (StringUtils.hasText(conversion.getUnit())) {
      sb.append(" / ").append(conversion.getUnit().trim());
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // pricing group (Req 6.3, 6.8, 6.13)
  // ---------------------------------------------------------------------------------------------

  private static Object pricingValue(
      String fieldKey, Pricing pricing, Product product, BarcodePool poolRow) {
    if (pricing == null) {
      // Pool-only code exposes its stored price as MRP (Req 6.8).
      if (product == null && poolRow != null && LabelFieldKeys.MRP.equals(fieldKey)) {
        return poolRow.getLabelPrice();
      }
      return null;
    }
    return switch (fieldKey) {
      case LabelFieldKeys.MRP -> pricing.getMaximumRetailPrice();
      case LabelFieldKeys.SELLING_PRICE -> pricing.getSellingPrice();
      case LabelFieldKeys.PTR -> pricing.getPriceToRetail();
      case LabelFieldKeys.COST_PRICE -> pricing.getCostPrice();
      case LabelFieldKeys.SALE_SCHEME -> schemeText(pricing.getSaleScheme());
      case LabelFieldKeys.GST_RATE -> gstRate(pricing.getSgst(), pricing.getCgst());
      default -> null;
    };
  }

  /** {@code "7+1"} for fixed-unit schemes, {@code "10%"} for percentage schemes, else null. */
  static String schemeText(Scheme scheme) {
    if (scheme == null) {
      return null;
    }
    String type = scheme.getSchemeType() == null ? "" : scheme.getSchemeType().trim();
    boolean percentage = SchemeType.PERCENTAGE.name().equalsIgnoreCase(type);
    boolean fixed = SchemeType.FIXED_UNITS.name().equalsIgnoreCase(type);
    if (percentage) {
      BigDecimal pct = scheme.getSchemePercentage();
      return pct == null ? null : LabelValueFormatter.format(pct, ValueType.PERCENTAGE);
    }
    if (fixed || type.isEmpty()) {
      Integer payFor = scheme.getSchemePayFor();
      Integer free = scheme.getSchemeFree();
      if (payFor != null && free != null) {
        return payFor + "+" + free;
      }
      if (!fixed && scheme.getSchemePercentage() != null) {
        return LabelValueFormatter.format(scheme.getSchemePercentage(), ValueType.PERCENTAGE);
      }
    }
    return null;
  }

  /** {@code sgst + cgst} as a BigDecimal; null when neither is a parseable number. */
  static BigDecimal gstRate(String sgst, String cgst) {
    BigDecimal s = parseDecimal(sgst);
    BigDecimal c = parseDecimal(cgst);
    if (s == null && c == null) {
      return null;
    }
    return (s == null ? BigDecimal.ZERO : s).add(c == null ? BigDecimal.ZERO : c);
  }

  private static BigDecimal parseDecimal(String text) {
    if (!StringUtils.hasText(text)) {
      return null;
    }
    try {
      return new BigDecimal(text.trim().replace("%", ""));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Object rateValue(Pricing pricing, String fieldKey) {
    if (pricing == null || pricing.getRates() == null) {
      return null;
    }
    String rateName = fieldKey.substring(LabelFieldKeys.PRICING_RATE_PREFIX.length());
    for (Rate rate : pricing.getRates()) {
      if (rate != null && rateName.equals(rate.getName())) {
        return rate.getPrice();
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // lot group (Req 6.4, 6.13)
  // ---------------------------------------------------------------------------------------------

  private static Object lotValue(
      String fieldKey, PrintableField field, Inventory lot, Map<String, Object> merged) {
    if (lot == null) {
      return null;
    }
    Object core =
        switch (fieldKey) {
          case LabelFieldKeys.BATCH_NO -> lot.getBatchNo();
          case LabelFieldKeys.EXPIRY_DATE -> lot.getExpiryDate();
          case LabelFieldKeys.RECEIVED_DATE ->
              lot.getReceivedDate() != null ? lot.getReceivedDate() : lot.getPurchaseDate();
          default -> null;
        };
    if (!isBlank(core)) {
      return core;
    }
    // Medical and other verticals store batch / expiry on the extension document under the same
    // apiKey as the core property.
    String apiKey =
        field != null && StringUtils.hasText(field.schemaApiKey()) ? field.schemaApiKey() : fieldKey;
    Object fromMerged = merged == null ? null : merged.get(apiKey);
    if (isBlank(fromMerged) && merged != null && !apiKey.equals(fieldKey)) {
      fromMerged = merged.get(fieldKey);
    }
    return isBlank(fromMerged) ? null : fromMerged;
  }

  // ---------------------------------------------------------------------------------------------
  // vertical group (Req 6.6)
  // ---------------------------------------------------------------------------------------------

  private static Object verticalValue(
      PrintableField field,
      String fieldKey,
      Product product,
      Map<String, Object> merged,
      FieldCatalog catalog) {
    String schemaKey =
        LabelFieldKeys.isVerticalKey(fieldKey)
            ? fieldKey.substring(LabelFieldKeys.VERTICAL_PREFIX.length())
            : fieldKey;
    String apiKey =
        field != null && StringUtils.hasText(field.schemaApiKey()) ? field.schemaApiKey() : schemaKey;

    boolean inventoryEntity = catalog != null && hasSchemaKey(catalog.inventorySchemaFields(), schemaKey);
    boolean productEntity = catalog != null && hasSchemaKey(catalog.productSchemaFields(), schemaKey);

    // Inventory-entity fields (or unknown entity): merged lot map by apiKey.
    if (inventoryEntity || !productEntity) {
      Object value = merged == null ? null : merged.get(apiKey);
      if (!isBlank(value)) {
        return value;
      }
      if (!productEntity) {
        return null;
      }
    }
    // Product-entity fields: read the property off the product bean.
    if (product == null) {
      return null;
    }
    BeanWrapperImpl wrapper = new BeanWrapperImpl(product);
    if (!wrapper.isReadableProperty(apiKey)) {
      return null;
    }
    return wrapper.getPropertyValue(apiKey);
  }

  private static boolean hasSchemaKey(List<VerticalSchemaField> fields, String key) {
    if (fields == null || key == null) {
      return false;
    }
    for (VerticalSchemaField f : fields) {
      if (f != null && key.equals(f.getKey())) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // shop group (Req 6.7)
  // ---------------------------------------------------------------------------------------------

  private static Object shopValue(String fieldKey, Shop shop) {
    if (shop == null) {
      return null;
    }
    return switch (fieldKey) {
      case LabelFieldKeys.SHOP_NAME -> shop.getName();
      case LabelFieldKeys.SHOP_TAGLINE -> shop.getTagline();
      case LabelFieldKeys.SHOP_PHONE -> shop.getContactPhone();
      case LabelFieldKeys.SHOP_EMAIL -> shop.getContactEmail();
      case LabelFieldKeys.SHOP_ADDRESS -> formatShopAddress(shop);
      case LabelFieldKeys.SHOP_GSTIN -> shop.getGstinNo();
      case LabelFieldKeys.SHOP_FSSAI -> shop.getFssai();
      case LabelFieldKeys.SHOP_DL_NO -> shop.getDlNo();
      default -> null;
    };
  }

  /** Same joining rule as {@code InvoiceSettingsService.formatShopAddress} (which is private). */
  static String formatShopAddress(Shop shop) {
    Location location = shop == null ? null : shop.getLocation();
    if (location == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    addIfText(parts, location.getPrimaryAddress());
    addIfText(parts, location.getSecondaryAddress());
    addIfText(parts, location.getCity());
    addIfText(parts, location.getState());
    addIfText(parts, location.getPin());
    return String.join(", ", parts);
  }

  private static void addIfText(List<String> parts, String value) {
    if (StringUtils.hasText(value)) {
      parts.add(value.trim());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static boolean isBlank(Object value) {
    if (value == null) {
      return true;
    }
    return value instanceof CharSequence cs && !StringUtils.hasText(cs);
  }
}
