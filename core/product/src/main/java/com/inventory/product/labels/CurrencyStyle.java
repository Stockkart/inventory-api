package com.inventory.product.labels;

/**
 * How a currency value is rendered on a sticker (Req 11).
 *
 * <ul>
 *   <li>{@link #RUPEE_SYMBOL}: the {@code ₹} symbol, e.g. {@code ₹120.00} (today's behaviour).
 *   <li>{@link #RS_PREFIX}: the ASCII {@code Rs.} prefix, e.g. {@code Rs. 120.00}, for printers or
 *       fonts that cannot render the rupee glyph.
 * </ul>
 *
 * <p>Serialized as the enum name to match the frontend {@code CurrencyStyle} type.
 */
public enum CurrencyStyle {
  RUPEE_SYMBOL,
  RS_PREFIX
}
