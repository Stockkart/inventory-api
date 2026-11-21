package com.inventory.documentservice.api;

/** Renders payslips. */
public interface PayslipDocuments {

  /** @return the payslip as a PDF */
  byte[] pdf(PayslipDocument document);
}
