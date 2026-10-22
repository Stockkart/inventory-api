package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.mapper.RefundMapper;
import com.inventory.product.rest.dto.response.RefundListResponse;
import com.inventory.product.rest.dto.response.RefundSummaryDto;
import com.inventory.product.rest.dto.response.RefundSummaryItemDto;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RefundServiceListTest {

  private static final String SHOP = "shop-1";

  @Mock private RefundRepository refundRepository;
  @Mock private PurchaseRepository purchaseRepository;
  @Mock(answer = Answers.CALLS_REAL_METHODS) private RefundMapper refundMapper;
  @Mock private HttpServletRequest httpRequest;

  @InjectMocks private RefundService service;

  @BeforeEach
  void setUp() {
    when(httpRequest.getAttribute("shopId")).thenReturn(SHOP);
  }

  private static RefundItem line(String name, String rate, String taxable, String half) {
    RefundItem item = new RefundItem();
    item.setName(name);
    item.setCgst(rate);
    item.setSgst(rate);
    item.setTaxableValue(new BigDecimal(taxable));
    item.setCgstAmount(new BigDecimal(half));
    item.setSgstAmount(new BigDecimal(half));
    return item;
  }

  private RefundSummaryDto listOnly(Refund refund) {
    when(refundRepository.findByShopId(eq(SHOP), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(refund)));
    RefundListResponse response = service.getRefunds(1, 20, null, null, null, null, httpRequest);
    return response.getRefunds().get(0);
  }

  @Test
  void theHistoryStatesTheSameTaxTotalsAsThePrintedNote() {
    Refund refund = new Refund();
    refund.setId("r1");
    refund.setPurchaseId("p1");
    refund.setRefundAmount(new BigDecimal("322"));
    refund.setRefundedItems(List.of(
        line("PARACETAMOL", "6", "100.00", "6.00"),
        line("COUGH SYRUP", "2.5", "199.55", "4.99")));

    RefundSummaryDto dto = listOnly(refund);

    assertEquals(new BigDecimal("299.55"), dto.getTaxableTotal());
    assertEquals(new BigDecimal("10.99"), dto.getCgstAmount());
    assertEquals(new BigDecimal("10.99"), dto.getSgstAmount());
    assertEquals(new BigDecimal("0.00"), dto.getIgstAmount());
    assertEquals(new BigDecimal("21.98"), dto.getTaxTotal());
    assertEquals(new BigDecimal("0.47"), dto.getRoundOff());
    assertEquals(false, dto.getInterstate());
  }

  @Test
  void eachHistoryLineCarriesItsGstRate() {
    Refund refund = new Refund();
    refund.setId("r1");
    refund.setPurchaseId("p1");
    refund.setRefundAmount(new BigDecimal("112"));
    refund.setTaxableTotal(new BigDecimal("100"));
    refund.setIgstAmount(new BigDecimal("12"));
    refund.setInterstate(true);
    RefundItem interstate = line("PARACETAMOL", "6", "100.00", "0");
    interstate.setIgstAmount(new BigDecimal("12.00"));
    refund.setRefundedItems(List.of(interstate));

    RefundSummaryDto dto = listOnly(refund);

    RefundSummaryItemDto item = dto.getRefundedItems().get(0);
    assertEquals(new BigDecimal("12"), item.getGstRatePct());
    assertEquals(new BigDecimal("12.00"), item.getIgstAmount());
    assertEquals(new BigDecimal("12.00"), dto.getIgstAmount());
    assertEquals(true, dto.getInterstate());
  }
}
