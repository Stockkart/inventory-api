package com.inventory.common.gst;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * A party's address as fields, so the state is a code and not a word buried in a line of text.
 * Every field is optional; {@link #getStateCode()} is the one the tax code cares about.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostalAddress {
  private String line1;
  private String line2;
  private String city;
  private String district;
  /** Two-digit GST state code, e.g. {@code 10} for Bihar. */
  private String stateCode;
  private String pincode;

  public boolean hasState() {
    return StringUtils.hasText(stateCode);
  }

  /** One readable line for lists and bills. */
  public String toDisplayLine() {
    StringBuilder sb = new StringBuilder();
    for (String part : new String[] {line1, line2, city, district}) {
      if (StringUtils.hasText(part)) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(part.trim());
      }
    }
    if (StringUtils.hasText(pincode)) {
      if (sb.length() > 0) sb.append(" - ");
      sb.append(pincode.trim());
    }
    return sb.toString();
  }
}
