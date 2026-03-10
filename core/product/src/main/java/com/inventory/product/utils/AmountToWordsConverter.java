package com.inventory.product.utils;

import java.math.BigDecimal;

/**
 * Utility class for converting numeric amounts to words in Indian currency format.
 * Supports conversion up to 99,99,99,999 (Indian numbering system).
 */
public class AmountToWordsConverter {

  /**
   * Convert amount to words in Indian currency format.
   * Example: 1014.50 -> "One Thousand and Fourteen Rupees and Fifty Paise only"
   * Example: 1014.00 -> "One Thousand and Fourteen Rupees only"
   *
   * @param amount the amount to convert
   * @return amount in words (e.g., "One Thousand and Fourteen Rupees and Fifty Paise only")
   */
  public static String convertAmountToWords(BigDecimal amount) {
    if (amount == null) {
      return "";
    }

    // Separate integer and decimal parts
    long rupees = amount.longValue();
    int paise = amount.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).intValue();

    StringBuilder result = new StringBuilder();

    // Convert rupees to words
    if (rupees > 0) {
      result.append(convertNumberToWords(rupees));
      result.append(" Rupees");
    } else {
      result.append("Zero Rupees");
    }

    // Convert paise to words
    if (paise > 0) {
      if (rupees > 0) {
        result.append(" and ");
      }
      result.append(convertNumberToWords(paise));
      result.append(" Paise");
    }

    result.append(" only");
    return result.toString();
  }

  /**
   * Convert a number to words.
   * Supports numbers up to 99,99,99,999 (Indian numbering system).
   *
   * @param number the number to convert
   * @return number in words
   */
  private static String convertNumberToWords(long number) {
    if (number == 0) {
      return "Zero";
    }

    // Handle negative numbers
    if (number < 0) {
      return "Negative " + convertNumberToWords(-number);
    }

    StringBuilder words = new StringBuilder();

    // Convert to string for easier processing
    String numStr = String.valueOf(number);

    // Indian numbering system: Crores, Lakhs, Thousands, Hundreds, Tens, Ones
    // Example: 12,34,56,789 -> 12 Crores, 34 Lakhs, 56 Thousands, 789

    if (numStr.length() > 7) {
      // Crores (8+ digits)
      int crores = (int) (number / 10000000);
      if (crores > 0) {
        words.append(convertThreeDigits(crores));
        words.append(" Crore");
        if (crores > 1) words.append("s");
        number = number % 10000000;
        if (number > 0) words.append(" ");
      }
    }

    if (numStr.length() > 5 || number >= 100000) {
      // Lakhs (6-7 digits)
      int lakhs = (int) (number / 100000);
      if (lakhs > 0) {
        words.append(convertThreeDigits(lakhs));
        words.append(" Lakh");
        if (lakhs > 1) words.append("s");
        number = number % 100000;
        if (number > 0) words.append(" ");
      }
    }

    if (numStr.length() > 3 || number >= 1000) {
      // Thousands (4-5 digits)
      int thousands = (int) (number / 1000);
      if (thousands > 0) {
        words.append(convertThreeDigits(thousands));
        words.append(" Thousand");
        if (thousands > 1) words.append("s");
        number = number % 1000;
        if (number > 0) words.append(" ");
      }
    }

    // Hundreds, Tens, Ones (1-3 digits)
    if (number > 0) {
      String threeDigits = convertThreeDigits((int) number);
      words.append(threeDigits);
    }

    return words.toString().trim();
  }

  /**
   * Convert a three-digit number (0-999) to words.
   *
   * @param number the three-digit number to convert
   * @return number in words
   */
  private static String convertThreeDigits(int number) {
    if (number == 0) {
      return "";
    }

    StringBuilder words = new StringBuilder();
    int hundreds = number / 100;
    int remainder = number % 100;

    // Hundreds place
    if (hundreds > 0) {
      words.append(getOnesWord(hundreds));
      words.append(" Hundred");
      if (remainder > 0) {
        words.append(" and ");
      }
    }

    // Tens and Ones place
    if (remainder > 0) {
      if (remainder < 20) {
        // Special cases: 1-19
        words.append(getOnesWord(remainder));
      } else {
        // 20-99
        int tens = remainder / 10;
        int ones = remainder % 10;
        words.append(getTensWord(tens));
        if (ones > 0) {
          words.append(" ");
          words.append(getOnesWord(ones));
        }
      }
    }

    return words.toString().trim();
  }

  /**
   * Get word for ones place (0-19).
   *
   * @param number the number (0-19)
   * @return word representation
   */
  private static String getOnesWord(int number) {
    String[] ones = {
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    };
    if (number >= 0 && number < ones.length) {
      return ones[number];
    }
    return "";
  }

  /**
   * Get word for tens place (20-90).
   *
   * @param number the tens digit (2-9)
   * @return word representation
   */
  private static String getTensWord(int number) {
    String[] tens = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };
    if (number >= 2 && number < tens.length) {
      return tens[number];
    }
    return "";
  }
}

