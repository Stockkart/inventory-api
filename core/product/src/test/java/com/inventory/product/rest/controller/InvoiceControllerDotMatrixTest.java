package com.inventory.product.rest.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.product.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InvoiceControllerDotMatrixTest {

  private static final String PURCHASE_ID = "purchase-1";
  private static final String SHOP_ID = "shop-1";

  @Mock private InvoiceService invoiceService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    InvoiceController controller = new InvoiceController();
    ReflectionTestUtils.setField(controller, "invoiceService", invoiceService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void returnsPlainTextForTheShopScopedPurchase() throws Exception {
    when(invoiceService.generateInvoiceText(eq(PURCHASE_ID), eq(SHOP_ID)))
        .thenReturn("INVOICE TEXT\n");

    mockMvc
        .perform(
            get("/api/v1/invoices/{purchaseId}/dot-matrix", PURCHASE_ID)
                .requestAttr("shopId", SHOP_ID))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/plain"))
        .andExpect(content().string("INVOICE TEXT\n"));
  }
}
