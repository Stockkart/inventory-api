package com.inventory.product.labels;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps a vertical schema field {@code type} to the {@link ValueType} used for label formatting
 * (Req 1.6).
 *
 * <table>
 *   <tr><th>schema type</th><th>value type</th></tr>
 *   <tr><td>{@code string}, {@code enum}</td><td>{@link ValueType#TEXT}</td></tr>
 *   <tr><td>{@code number}</td><td>{@link ValueType#NUMBER}</td></tr>
 *   <tr><td>{@code money}, {@code currency}</td><td>{@link ValueType#CURRENCY}</td></tr>
 *   <tr><td>{@code date}</td><td>{@link ValueType#DATE}</td></tr>
 *   <tr><td>{@code percent}, {@code percentage}</td><td>{@link ValueType#PERCENTAGE}</td></tr>
 *   <tr><td>anything else ({@code boolean}, {@code list}, {@code object}, {@code null}, …)</td>
 *       <td>excluded (empty)</td></tr>
 * </table>
 *
 * <p>Matching is case-insensitive and ignores surrounding whitespace.
 */
@Component
public class VerticalValueTypeMapper {

  /**
   * @param schemaType the schema field {@code type}, may be {@code null}
   * @return the mapped value type, or empty when the type is not printable
   */
  public Optional<ValueType> map(String schemaType) {
    if (schemaType == null) {
      return Optional.empty();
    }
    String normalized = schemaType.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "string", "enum" -> Optional.of(ValueType.TEXT);
      case "number" -> Optional.of(ValueType.NUMBER);
      case "money", "currency" -> Optional.of(ValueType.CURRENCY);
      case "date" -> Optional.of(ValueType.DATE);
      case "percent", "percentage" -> Optional.of(ValueType.PERCENTAGE);
      default -> Optional.empty();
    };
  }
}
