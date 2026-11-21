package com.inventory.hr.service;

/** A rendered HR document: payslip PDF, register, export. */
public record HrFile(String fileName, String contentType, byte[] bytes) {

  public static final String PDF = "application/pdf";
  public static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}
