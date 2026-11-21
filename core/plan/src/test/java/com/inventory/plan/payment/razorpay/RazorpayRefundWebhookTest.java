package com.inventory.plan.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.config.PaymentProperties;
import com.inventory.plan.payment.dto.WebhookHandleCommand;
import com.inventory.plan.payment.dto.WebhookHandleResult;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RazorpayRefundWebhookTest {

  private static final String SECRET = "whsec";

  private RazorpayPaymentGateway gateway;

  @BeforeEach
  void setUp() {
    PaymentProperties properties = new PaymentProperties();
    properties.getRazorpay().setWebhookSecret(SECRET);
    gateway = new RazorpayPaymentGateway(properties, mock(RazorpayApiClient.class), new ObjectMapper(),
        mock(MetricsWrapper.class));
  }

  private WebhookHandleResult handle(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    return gateway.handleWebhook(WebhookHandleCommand.builder().rawBody(body)
        .headers(Map.of("x-razorpay-signature", signature)).build());
  }

  @Test
  void readsAProcessedRefund() throws Exception {
    WebhookHandleResult result = handle("""
        {"event":"refund.processed","payload":{
          "refund":{"entity":{"id":"rfnd_1","payment_id":"pay_1","amount":199950}},
          "payment":{"entity":{"id":"pay_1","order_id":"order_rzp"}}}}""");

    assertThat(result.isProcessed()).isTrue();
    assertThat(result.getEventType()).isEqualTo(WebhookHandleResult.EventType.REFUND_PROCESSED);
    assertThat(result.getProviderRefundId()).isEqualTo("rfnd_1");
    assertThat(result.getProviderPaymentId()).isEqualTo("pay_1");
    assertThat(result.getProviderOrderId()).isEqualTo("order_rzp");
    assertThat(result.getAmount()).isEqualByComparingTo("1999.50");
  }

  @Test
  void readsALostDispute() throws Exception {
    WebhookHandleResult result = handle("""
        {"event":"payment.dispute.lost","payload":{
          "dispute":{"entity":{"id":"disp_1","payment_id":"pay_1","amount":999900}}}}""");

    assertThat(result.getEventType()).isEqualTo(WebhookHandleResult.EventType.DISPUTE_LOST);
    assertThat(result.getAmount()).isEqualByComparingTo("9999.00");
  }

  @Test
  void ignoresARefundWithoutAPayment() throws Exception {
    assertThat(handle("""
        {"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_1","amount":100}}}}""")
        .isProcessed()).isFalse();
  }
}
