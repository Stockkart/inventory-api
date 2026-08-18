package com.inventory.taxation.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.StringUtils;

/**
 * GST state codes and the {@code NN-Name} form the GST portal expects.
 *
 * <p>The portal accepts place of supply only as a code-prefixed label such as
 * {@code 10-Bihar}; a bare state name is rejected on upload. Shop addresses store
 * the name alone ({@link com.inventory.product.domain.model.Location#getState()}),
 * so a name-to-code lookup is needed to render it.
 *
 * <p>The first two digits of a GSTIN are the registered state code, which is why
 * a recipient's place of supply can be derived from their GSTIN without holding
 * their address.
 */
public final class GstStateCode {

  private static final Map<String, String> BY_CODE = new LinkedHashMap<>();
  private static final Map<String, String> CODE_BY_NAME = new LinkedHashMap<>();

  static {
    put("01", "Jammu and Kashmir");
    put("02", "Himachal Pradesh");
    put("03", "Punjab");
    put("04", "Chandigarh");
    put("05", "Uttarakhand");
    put("06", "Haryana");
    put("07", "Delhi");
    put("08", "Rajasthan");
    put("09", "Uttar Pradesh");
    put("10", "Bihar");
    put("11", "Sikkim");
    put("12", "Arunachal Pradesh");
    put("13", "Nagaland");
    put("14", "Manipur");
    put("15", "Mizoram");
    put("16", "Tripura");
    put("17", "Meghalaya");
    put("18", "Assam");
    put("19", "West Bengal");
    put("20", "Jharkhand");
    put("21", "Odisha");
    put("22", "Chhattisgarh");
    put("23", "Madhya Pradesh");
    put("24", "Gujarat");
    put("26", "Dadra and Nagar Haveli and Daman and Diu");
    put("27", "Maharashtra");
    put("29", "Karnataka");
    put("30", "Goa");
    put("31", "Lakshadweep");
    put("32", "Kerala");
    put("33", "Tamil Nadu");
    put("34", "Puducherry");
    put("35", "Andaman and Nicobar Islands");
    put("36", "Telangana");
    put("37", "Andhra Pradesh");
    put("38", "Ladakh");
    put("97", "Other Territory");
    put("99", "Centre Jurisdiction");
  }

  private GstStateCode() {}

  private static void put(String code, String name) {
    BY_CODE.put(code, name);
    CODE_BY_NAME.put(name.toUpperCase(), code);
  }

  /** The two-digit state code carried by a GSTIN, or empty when it is not one we know. */
  public static String codeFromGstin(String gstin) {
    if (!StringUtils.hasText(gstin) || gstin.trim().length() < 2) {
      return "";
    }
    String code = gstin.trim().substring(0, 2);
    return BY_CODE.containsKey(code) ? code : "";
  }

  /** The code for a state name, tolerating case and surrounding whitespace. */
  public static String codeFromName(String stateName) {
    if (!StringUtils.hasText(stateName)) {
      return "";
    }
    return CODE_BY_NAME.getOrDefault(stateName.trim().toUpperCase(), "");
  }

  /**
   * Render as {@code 10-Bihar}.
   *
   * <p>Accepts either a code or a name, and passes through anything already in
   * {@code NN-Name} form so a value that has been formatted once is not mangled
   * by being formatted again.
   */
  public static String format(String codeOrName) {
    if (!StringUtils.hasText(codeOrName)) {
      return "";
    }
    String value = codeOrName.trim();

    int dash = value.indexOf('-');
    if (dash == 2 && Character.isDigit(value.charAt(0)) && Character.isDigit(value.charAt(1))) {
      String code = value.substring(0, 2);
      String name = BY_CODE.get(code);
      return name != null ? code + "-" + name : value;
    }

    String name = BY_CODE.get(value);
    if (name != null) {
      return value + "-" + name;
    }

    String code = codeFromName(value);
    return StringUtils.hasText(code) ? code + "-" + BY_CODE.get(code) : value;
  }

  /**
   * Place of supply for a supply line, as {@code NN-Name}.
   *
   * <p>For a registered recipient the place of supply is where that recipient is
   * registered, so it comes from their GSTIN. For an unregistered buyer there is
   * no such record and the supplier's own state applies, which is what
   * {@code sellerState} provides.
   */
  public static String placeOfSupply(String recipientGstin, String sellerState) {
    String code = codeFromGstin(recipientGstin);
    return StringUtils.hasText(code) ? format(code) : format(sellerState);
  }
}
