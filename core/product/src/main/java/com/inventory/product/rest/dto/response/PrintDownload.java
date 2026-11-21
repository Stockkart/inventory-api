package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The printer file handed over when there is no bridge. {@code .prn}, not {@code .txt}: the text
 * carries the printer's bold and double-width codes, and Windows opens a {@code .txt} in Notepad,
 * which prints those codes as characters.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintDownload {
  private String filename;
  private String content;
}
