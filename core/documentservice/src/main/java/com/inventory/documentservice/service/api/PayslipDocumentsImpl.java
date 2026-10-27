package com.inventory.documentservice.service.api;

import com.inventory.documentservice.api.PayslipDocument;
import com.inventory.documentservice.api.PayslipDocuments;
import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.service.hr.PayslipPdfService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link PayslipDocuments} for other modules: copies the api record into the renderer's own
 * request class, field for field, and hands it to the existing payslip renderer unchanged.
 */
@Service
@RequiredArgsConstructor
public class PayslipDocumentsImpl implements PayslipDocuments {

  private final PayslipPdfService payslipPdfService;

  @Override
  public byte[] pdf(PayslipDocument document) {
    return payslipPdfService.generate(toRequest(document));
  }

  /** Every field is set, nulls included, so the renderer sees exactly what the caller gave. */
  static PayslipDocumentRequest toRequest(PayslipDocument d) {
    if (d == null) {
      return null;
    }
    PayslipDocumentRequest r = new PayslipDocumentRequest();
    r.setShopName(d.shopName());
    r.setShopAddress(d.shopAddress());
    r.setEmployeeName(d.employeeName());
    r.setEmployeeCode(d.employeeCode());
    r.setDesignation(d.designation());
    r.setPeriodLabel(d.periodLabel());
    r.setDayRows(rows(d.dayRows()));
    r.setEarnings(items(d.earnings()));
    r.setDeductions(items(d.deductions()));
    r.setGrossPay(d.grossPay());
    r.setTotalDeductions(d.totalDeductions());
    r.setNetBeforeRounding(d.netBeforeRounding());
    r.setRounding(d.rounding());
    r.setNetPay(d.netPay());
    r.setNetPayInWords(d.netPayInWords());
    r.setAdvanceBalance(d.advanceBalance());
    r.setPaymentNote(d.paymentNote());
    r.setGeneratedAtLabel(d.generatedAtLabel());
    return r;
  }

  private static List<PayslipDocumentRequest.Row> rows(List<PayslipDocument.Row> in) {
    if (in == null) {
      return null;
    }
    List<PayslipDocumentRequest.Row> out = new ArrayList<>(in.size());
    for (PayslipDocument.Row row : in) {
      out.add(row == null ? null : new PayslipDocumentRequest.Row(row.label(), row.value()));
    }
    return out;
  }

  private static List<PayslipDocumentRequest.Item> items(List<PayslipDocument.Item> in) {
    if (in == null) {
      return null;
    }
    List<PayslipDocumentRequest.Item> out = new ArrayList<>(in.size());
    for (PayslipDocument.Item item : in) {
      out.add(item == null ? null : new PayslipDocumentRequest.Item(item.label(), item.amount()));
    }
    return out;
  }
}
