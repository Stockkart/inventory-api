package com.inventory.plan.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.service.OrderPricingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Pins the JSON field names that platform/contracts QuoteRequest/QuoteResponse mirror. */
@ExtendWith(MockitoExtension.class)
class PlanOrderControllerTest {

  @Mock
  private OrderPricingService orderPricingService;

  @InjectMocks
  private PlanOrderController controller;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void quoteContract() throws Exception {
    ArgumentCaptor<QuoteRequest> captor = ArgumentCaptor.forClass(QuoteRequest.class);
    when(orderPricingService.quote(org.mockito.ArgumentMatchers.any(), captor.capture())).thenReturn(QuoteResponse.builder()
        .items(List.of(QuoteResponse.QuoteItem.builder()
            .type("PLAN").code("PROFESSIONAL").name("Professional").quantity(1)
            .unitPrice(new BigDecimal("9999")).discount(BigDecimal.ZERO)
            .lineTotal(new BigDecimal("9999")).itemSource("MANUAL").build()))
        .subtotal(new BigDecimal("9999"))
        .discountTotal(BigDecimal.ZERO)
        .walletCredit(BigDecimal.ZERO)
        .taxInclusive(true)
        .grandTotal(new BigDecimal("9999"))
        .currency("INR")
        .durationMonths(12)
        .pricingVersion(1)
        .quotedAt(Instant.parse("2026-09-27T10:00:00Z"))
        .expiresAt(Instant.parse("2026-09-27T10:15:00Z"))
        .build());

    mvc.perform(post("/api/v1/plans/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"planCode":"PROFESSIONAL","durationMonths":12,
                 "addOns":[],"voucherCodes":[],"applyWalletCredit":true}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].type").value("PLAN"))
        .andExpect(jsonPath("$.data.items[0].code").value("PROFESSIONAL"))
        .andExpect(jsonPath("$.data.items[0].unitPrice").value(9999))
        .andExpect(jsonPath("$.data.items[0].lineTotal").value(9999))
        .andExpect(jsonPath("$.data.items[0].itemSource").value("MANUAL"))
        .andExpect(jsonPath("$.data.subtotal").value(9999))
        .andExpect(jsonPath("$.data.discountTotal").value(0))
        .andExpect(jsonPath("$.data.walletCredit").value(0))
        .andExpect(jsonPath("$.data.taxInclusive").value(true))
        .andExpect(jsonPath("$.data.grandTotal").value(9999))
        .andExpect(jsonPath("$.data.currency").value("INR"))
        .andExpect(jsonPath("$.data.durationMonths").value(12))
        .andExpect(jsonPath("$.data.pricingVersion").value(1))
        .andExpect(jsonPath("$.data.quotedAt").exists())
        .andExpect(jsonPath("$.data.expiresAt").exists());

    assertThat(captor.getValue().getPlanCode()).isEqualTo("PROFESSIONAL");
    assertThat(captor.getValue().getApplyWalletCredit()).isTrue();
  }
}
