package com.commerce.synthetic.merchant;

import com.commerce.synthetic.api.PaymentAuthorizeRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "commerce.scenario.merchant-timeout-ms=50"
})
@AutoConfigureMockMvc
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authorizesPaymentNormally() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();

        PaymentAuthorizeRequest req = new PaymentAuthorizeRequest(runId, journeyId, cartId);

        mockMvc.perform(post("/internal/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "NORMAL")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "false")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_AUTHORIZED"))
                .andExpect(jsonPath("$.cart_id").value(cartId));
    }

    @Test
    void delaysWhenMerchantTimeoutScenarioActive() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();

        PaymentAuthorizeRequest req = new PaymentAuthorizeRequest(runId, journeyId, cartId);

        mockMvc.perform(post("/internal/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "MERCHANT_TIMEOUT")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_AUTHORIZED"));
    }
}
