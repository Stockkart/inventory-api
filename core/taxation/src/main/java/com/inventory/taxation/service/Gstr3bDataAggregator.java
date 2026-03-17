package com.inventory.taxation.service;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.domain.gstr3b.Gstr3bReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.domain.model.GstRefundLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates GSTR-1 and GSTR-2 data into GSTR-3B monthly summary.
 */
@Service
@Slf4j
public class Gstr3bDataAggregator {

  @Autowired
  private Gstr1DataAggregator gstr1Aggregator;
  @Autowired
  private Gstr2DataAggregator gstr2Aggregator;
  @Autowired
  private ShopRepository shopRepository;

  public Gstr3bReportContext buildContext(String shopId, String period) {
    Shop shop = shopRepository.findById(shopId)
        .orElseThrow(() -> new IllegalArgumentException("Shop not found: " + shopId));

    Gstr1ReportContext g1 = gstr1Aggregator.buildContext(shopId, period);
    Gstr2ReportContext g2 = gstr2Aggregator.buildContext(shopId, period);

    String shopState = shop.getLocation() != null && StringUtils.hasText(shop.getLocation().getState())
        ? shop.getLocation().getState()
        : "";

    Gstr3bReportContext.Gstr3bSection31 s31 = buildSection31(g1, shopState);
    List<Gstr3bReportContext.Gstr3bInterStateSupply> interState = buildInterStateSupplies(g1);
    Gstr3bReportContext.Gstr3bSection4 s4 = buildSection4(g2);
    Gstr3bReportContext.Gstr3bSection5 s5 = buildSection5(g2);
    Gstr3bReportContext.Gstr3bSection61 s61 = buildSection61(s31, s4);

    return Gstr3bReportContext.builder()
        .shopId(shopId)
        .shopGstin(shop.getGstinNo() != null ? shop.getGstinNo() : "")
        .legalName(shop.getName() != null ? shop.getName() : "")
        .period(period)
        .year(g1.getYear())
        .month(g1.getMonth())
        .section31(s31)
        .interStateSupplies(interState)
        .section4(s4)
        .section5(s5)
        .section61(s61)
        .build();
  }

  private Gstr3bReportContext.Gstr3bSection31 buildSection31(Gstr1ReportContext g1, String shopState) {
    BigDecimal taxableVal = BigDecimal.ZERO;
    BigDecimal igst = BigDecimal.ZERO;
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    BigDecimal cess = BigDecimal.ZERO;

    for (GstInvoiceLine line : g1.getB2bLines()) {
      taxableVal = add(taxableVal, line.getTaxableValue());
      igst = add(igst, line.getIntegratedTaxAmount());
      cgst = add(cgst, line.getCentralTaxAmount());
      sgst = add(sgst, line.getStateTaxAmount());
      cess = add(cess, line.getCessAmount());
    }
    for (GstInvoiceLine line : g1.getB2clLines()) {
      taxableVal = add(taxableVal, line.getTaxableValue());
      igst = add(igst, line.getIntegratedTaxAmount());
      cgst = add(cgst, line.getCentralTaxAmount());
      sgst = add(sgst, line.getStateTaxAmount());
      cess = add(cess, line.getCessAmount());
    }
    for (GstInvoiceLine line : g1.getB2csLines()) {
      taxableVal = add(taxableVal, line.getTaxableValue());
      igst = add(igst, line.getIntegratedTaxAmount());
      cgst = add(cgst, line.getCentralTaxAmount());
      sgst = add(sgst, line.getStateTaxAmount());
      cess = add(cess, line.getCessAmount());
    }

    for (GstRefundLine line : g1.getCdnrLines()) {
      BigDecimal tv = line.getTaxableValue() != null ? line.getTaxableValue() : BigDecimal.ZERO;
      BigDecimal rate = line.getRate() != null ? line.getRate() : BigDecimal.ZERO;
      taxableVal = taxableVal.subtract(tv);
      BigDecimal halfRate = rate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
      BigDecimal taxPerHalf = tv.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      cgst = cgst.subtract(taxPerHalf);
      sgst = sgst.subtract(taxPerHalf);
    }
    for (GstRefundLine line : g1.getCdnurLines()) {
      BigDecimal tv = line.getTaxableValue() != null ? line.getTaxableValue() : BigDecimal.ZERO;
      BigDecimal rate = line.getRate() != null ? line.getRate() : BigDecimal.ZERO;
      taxableVal = taxableVal.subtract(tv);
      BigDecimal halfRate = rate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
      BigDecimal taxPerHalf = tv.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      cgst = cgst.subtract(taxPerHalf);
      sgst = sgst.subtract(taxPerHalf);
    }

    BigDecimal zeroVal = BigDecimal.ZERO;
    BigDecimal zeroIgst = BigDecimal.ZERO;
    for (GstInvoiceLine line : g1.getExpLines()) {
      zeroVal = add(zeroVal, line.getTaxableValue());
      zeroIgst = add(zeroIgst, line.getIntegratedTaxAmount());
    }

    BigDecimal nilVal = BigDecimal.ZERO;
    for (var line : g1.getExempLines()) {
      nilVal = add(nilVal, line.getNilRatedSupplies());
      nilVal = add(nilVal, line.getExemptedOtherThanNilOrNonGst());
      nilVal = add(nilVal, line.getNonGstSupplies());
    }

    return new Gstr3bReportContext.Gstr3bSection31(
        taxableVal, igst, cgst, sgst, cess,
        zeroVal, zeroIgst,
        nilVal,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private List<Gstr3bReportContext.Gstr3bInterStateSupply> buildInterStateSupplies(Gstr1ReportContext g1) {
    Map<String, Gstr3bReportContext.Gstr3bInterStateSupply> byPlace = new LinkedHashMap<>();

    for (GstInvoiceLine line : g1.getB2bLines()) {
      BigDecimal igst = line.getIntegratedTaxAmount() != null ? line.getIntegratedTaxAmount() : BigDecimal.ZERO;
      if (igst.compareTo(BigDecimal.ZERO) > 0) {
        String pos = StringUtils.hasText(line.getPlaceOfSupply()) ? line.getPlaceOfSupply() : "Other";
        byPlace.merge(pos,
            new Gstr3bReportContext.Gstr3bInterStateSupply(
                pos,
                line.getTaxableValue() != null ? line.getTaxableValue() : BigDecimal.ZERO,
                igst),
            (a, b) -> new Gstr3bReportContext.Gstr3bInterStateSupply(
                pos, add(a.getTaxableValue(), b.getTaxableValue()), add(a.getIntegratedTax(), b.getIntegratedTax())));
      }
    }
    for (GstInvoiceLine line : g1.getB2clLines()) {
      String pos = StringUtils.hasText(line.getPlaceOfSupply()) ? line.getPlaceOfSupply() : "Other";
      byPlace.merge(pos,
          new Gstr3bReportContext.Gstr3bInterStateSupply(
              pos,
              line.getTaxableValue() != null ? line.getTaxableValue() : BigDecimal.ZERO,
              line.getIntegratedTaxAmount() != null ? line.getIntegratedTaxAmount() : BigDecimal.ZERO),
          (a, b) -> new Gstr3bReportContext.Gstr3bInterStateSupply(
              pos, add(a.getTaxableValue(), b.getTaxableValue()), add(a.getIntegratedTax(), b.getIntegratedTax())));
    }
    for (GstInvoiceLine line : g1.getB2csLines()) {
      BigDecimal igst = line.getIntegratedTaxAmount() != null ? line.getIntegratedTaxAmount() : BigDecimal.ZERO;
      if (igst.compareTo(BigDecimal.ZERO) > 0) {
        String pos = StringUtils.hasText(line.getPlaceOfSupply()) ? line.getPlaceOfSupply() : "Other";
        byPlace.merge(pos,
            new Gstr3bReportContext.Gstr3bInterStateSupply(
                pos,
                line.getTaxableValue() != null ? line.getTaxableValue() : BigDecimal.ZERO,
                igst),
            (a, b) -> new Gstr3bReportContext.Gstr3bInterStateSupply(
                pos, add(a.getTaxableValue(), b.getTaxableValue()), add(a.getIntegratedTax(), b.getIntegratedTax())));
      }
    }

    return new ArrayList<>(byPlace.values());
  }

  private Gstr3bReportContext.Gstr3bSection4 buildSection4(Gstr2ReportContext g2) {
    BigDecimal otherIgst = BigDecimal.ZERO;
    BigDecimal otherCgst = BigDecimal.ZERO;
    BigDecimal otherSgst = BigDecimal.ZERO;
    for (var line : g2.getB2bLines()) {
      otherIgst = add(otherIgst, line.getAvailedItcIntegrated());
      otherCgst = add(otherCgst, line.getAvailedItcCentral());
      otherSgst = add(otherSgst, line.getAvailedItcStateUt());
    }
    for (var line : g2.getB2burLines()) {
      otherIgst = add(otherIgst, line.getAvailedItcIntegrated());
      otherCgst = add(otherCgst, line.getAvailedItcCentral());
      otherSgst = add(otherSgst, line.getAvailedItcStateUt());
    }

    BigDecimal revIgst = BigDecimal.ZERO;
    BigDecimal revCgst = BigDecimal.ZERO;
    BigDecimal revSgst = BigDecimal.ZERO;
    for (var line : g2.getItcrLines()) {
      revIgst = add(revIgst, line.getItcIntegratedTaxAmount());
      revCgst = add(revCgst, line.getItcCentralTaxAmount());
      revSgst = add(revSgst, line.getItcStateUtTaxAmount());
    }
    for (var line : g2.getCdnrLines()) {
      revCgst = add(revCgst, line.getCentralTaxPaid());
      revSgst = add(revSgst, line.getStateUtTaxPaid());
    }

    BigDecimal netIgst = otherIgst.subtract(revIgst);
    BigDecimal netCgst = otherCgst.subtract(revCgst);
    BigDecimal netSgst = otherSgst.subtract(revSgst);

    return Gstr3bReportContext.Gstr3bSection4.builder()
        .itcOtherIgst(otherIgst)
        .itcOtherCgst(otherCgst)
        .itcOtherSgst(otherSgst)
        .itcReversedOthersIgst(revIgst)
        .itcReversedOthersCgst(revCgst)
        .itcReversedOthersSgst(revSgst)
        .build();
  }

  private Gstr3bReportContext.Gstr3bSection5 buildSection5(Gstr2ReportContext g2) {
    BigDecimal interState = BigDecimal.ZERO;
    BigDecimal intraState = BigDecimal.ZERO;
    for (var line : g2.getExempLines()) {
      interState = add(interState, line.getCompositionTaxablePerson());
      interState = add(interState, line.getNilRatedSupplies());
      interState = add(interState, line.getExemptedOtherThanNilOrNonGst());
      intraState = add(intraState, line.getNonGstSupplies());
    }
    return new Gstr3bReportContext.Gstr3bSection5(interState, intraState, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  private Gstr3bReportContext.Gstr3bSection61 buildSection61(
      Gstr3bReportContext.Gstr3bSection31 s31,
      Gstr3bReportContext.Gstr3bSection4 s4) {

    if (s31 == null || s4 == null) {
      return Gstr3bReportContext.Gstr3bSection61.builder().build();
    }

    BigDecimal igstPay = orZero(s31.getOutwardTaxableIgst());
    BigDecimal cgstPay = orZero(s31.getOutwardTaxableCgst());
    BigDecimal sgstPay = orZero(s31.getOutwardTaxableSgst());
    BigDecimal cessPay = orZero(s31.getOutwardTaxableCess());

    BigDecimal itcIgst = s4.getItcOtherIgst() != null ? s4.getItcOtherIgst() : BigDecimal.ZERO;
    BigDecimal itcCgst = s4.getItcOtherCgst() != null ? s4.getItcOtherCgst() : BigDecimal.ZERO;
    BigDecimal itcSgst = s4.getItcOtherSgst() != null ? s4.getItcOtherSgst() : BigDecimal.ZERO;

    BigDecimal igstByItc = igstPay.min(itcIgst);
    BigDecimal igstByCash = igstPay.subtract(igstByItc);
    BigDecimal itcRemaining = itcIgst.subtract(igstByItc);

    BigDecimal cgstByItcIgst = BigDecimal.ZERO;
    BigDecimal cgstByItcCgst = BigDecimal.ZERO;
    BigDecimal cgstByItcSgst = BigDecimal.ZERO;
    if (cgstPay.compareTo(BigDecimal.ZERO) > 0) {
      if (itcRemaining.compareTo(BigDecimal.ZERO) > 0) {
        cgstByItcIgst = cgstPay.min(itcRemaining);
        itcRemaining = itcRemaining.subtract(cgstByItcIgst);
      }
      if (itcRemaining.compareTo(BigDecimal.ZERO) <= 0 && itcCgst.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal cgstRem = cgstPay.subtract(cgstByItcIgst);
        cgstByItcCgst = cgstRem.min(itcCgst);
      } else if (itcCgst.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal cgstRem = cgstPay.subtract(cgstByItcIgst);
        cgstByItcCgst = cgstRem.min(itcCgst);
      }
    }
    BigDecimal cgstByCash = cgstPay.subtract(cgstByItcIgst).subtract(cgstByItcCgst).subtract(cgstByItcSgst);

    BigDecimal sgstByItcIgst = BigDecimal.ZERO;
    BigDecimal sgstByItcCgst = BigDecimal.ZERO;
    BigDecimal sgstByItcSgst = BigDecimal.ZERO;
    if (sgstPay.compareTo(BigDecimal.ZERO) > 0 && itcSgst.compareTo(BigDecimal.ZERO) > 0) {
      sgstByItcSgst = sgstPay.min(itcSgst);
    }
    BigDecimal sgstByCash = sgstPay.subtract(sgstByItcSgst);

    return Gstr3bReportContext.Gstr3bSection61.builder()
        .igstPayable(igstPay)
        .igstPaidByItc(igstByItc)
        .igstPaidByCash(igstByCash)
        .cgstPayable(cgstPay)
        .cgstPaidByItcIgst(cgstByItcIgst)
        .cgstPaidByItcCgst(cgstByItcCgst)
        .cgstPaidByItcSgst(cgstByItcSgst)
        .cgstPaidByCash(cgstByCash.max(BigDecimal.ZERO))
        .sgstPayable(sgstPay)
        .sgstPaidByItcSgst(sgstByItcSgst)
        .sgstPaidByCash(sgstByCash.max(BigDecimal.ZERO))
        .cessPayable(cessPay)
        .build();
  }

  private static BigDecimal add(BigDecimal a, BigDecimal b) {
    a = a != null ? a : BigDecimal.ZERO;
    b = b != null ? b : BigDecimal.ZERO;
    return a.add(b);
  }

  private static BigDecimal orZero(BigDecimal b) {
    return b != null ? b : BigDecimal.ZERO;
  }
}
