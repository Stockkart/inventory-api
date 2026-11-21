package com.inventory.product.labels;

/**
 * The physical medium stickers are printed on (Req 10.1).
 *
 * <ul>
 *   <li>{@link #ROLL}: continuous label roll, one sticker per page (today's behaviour).
 *   <li>{@link #SHEET}: a fixed page (A4/Letter) holding a grid of stickers, laid out per a {@link
 *       SheetPreset}.
 * </ul>
 *
 * <p>Serialized as the enum name ({@code "ROLL"}/{@code "SHEET"}) to match the frontend {@code
 * PrintMedia} type.
 */
public enum PrintMedia {
  ROLL,
  SHEET
}
