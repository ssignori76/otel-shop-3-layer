package com.commerce.synthetic.cart;

import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CreateCartRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cart_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listsThreeDemoProductsFromDatabase() throws Exception {
        mockMvc.perform(get("/internal/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].sku").isNotEmpty());
    }

    @Test
    void createsAndReadsCartWithStateTransitions() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        CreateCartRequest req = new CreateCartRequest(runId, journeyId, "SKU-100", 1);

        MvcResult createResult = mockMvc.perform(post("/internal/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "NORMAL")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "false")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CART_CREATED"))
                .andReturn();

        CartResponse created = objectMapper.readValue(createResult.getResponse().getContentAsString(), CartResponse.class);
        assertThat(created.cartId()).isNotBlank();
        assertThat(created.journeyId()).isEqualTo(journeyId);

        mockMvc.perform(get("/internal/carts/" + created.cartId())
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart_id").value(created.cartId()))
                .andExpect(jsonPath("$.status").value("CART_READ"));

        mockMvc.perform(get("/internal/carts/" + UUID.randomUUID())
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("CART_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidCartRequest() throws Exception {
        CreateCartRequest invalid = new CreateCartRequest("bad", "bad", "", 0);
        mockMvc.perform(post("/internal/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));
    }
}
