package com.inventory.documentservice.api;

/** Renders MIS reports. */
public interface MisDocuments {

  /** @return the report as an Excel workbook (.xlsx) */
  byte[] excel(MisTabularDocument document);

  /** @return the report as a PDF */
  byte[] pdf(MisTabularDocument document);
}
