package com.commerce.synthetic.checkout;

import com.commerce.synthetic.api.CheckoutRequest;
import com.commerce.synthetic.api.PaymentAuthorizeRequest;
import com.commerce.synthetic.api.PaymentAuthorizeResponse;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkout_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class CheckoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OrderRepository orderRepository;

    @MockBean
    private OrderPublisher orderPublisher;

    @MockBean
    private RestClient merchantRestClient;

    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    private RestClient.RequestBodySpec requestBodySpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUpMocks() {
        requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        requestBodySpec = mock(RestClient.RequestBodySpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(merchantRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(PaymentAuthorizeRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void processesNormalCheckoutAndPublishesOrder() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();

        when(responseSpec.body(PaymentAuthorizeResponse.class)).thenReturn(
                new PaymentAuthorizeResponse("auth-123", journeyId, cartId, "PAYMENT_AUTHORIZED", Instant.now().toString())
        );
        doNothing().when(orderPublisher).publishOrderWithConfirm(any(), any(ScenarioContext.class));

        CheckoutRequest req = new CheckoutRequest(runId, journeyId, cartId);

        mockMvc.perform(post("/internal/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "NORMAL")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "false")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.payment_authorization_id").value("auth-123"));

        verify(orderRepository).insertOrder(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyInt());
        verify(orderRepository).updateOrderStatus(anyString(), org.mockito.ArgumentMatchers.eq("PUBLISHED"));
    }

    @Test
    void beErrorScenarioFailsBeforeDatabaseAndMerchantAndRabbit() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();
        CheckoutRequest req = new CheckoutRequest(runId, journeyId, cartId);

        mockMvc.perform(post("/internal/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "BE_ERROR")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error_code").value("BE_ERROR"));

        verify(orderRepository, never()).verifyDatabaseAndApplyDbScenario(anyBoolean(), anyLong(), anyBoolean(), anyInt());
        verify(merchantRestClient, never()).post();
        verify(orderPublisher, never()).publishOrderWithConfirm(any(), any());
    }

    @Test
    void dbTimeoutScenarioReturns504WithoutMerchantOrPublish() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();
        CheckoutRequest req = new CheckoutRequest(runId, journeyId, cartId);

        doThrow(new QueryTimeoutException("Statement cancelled due to timeout"))
                .when(orderRepository)
                .verifyDatabaseAndApplyDbScenario(anyBoolean(), anyLong(), anyBoolean(), anyInt());

        mockMvc.perform(post("/internal/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "DB_TIMEOUT")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error_code").value("DB_TIMEOUT"));

        verify(merchantRestClient, never()).post();
        verify(orderPublisher, never()).publishOrderWithConfirm(any(), any());
    }

    @Test
    void merchantTimeoutScenarioReturns504WithoutPublish() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();
        CheckoutRequest req = new CheckoutRequest(runId, journeyId, cartId);

        when(responseSpec.body(PaymentAuthorizeResponse.class))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        mockMvc.perform(post("/internal/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "MERCHANT_TIMEOUT")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error_code").value("MERCHANT_TIMEOUT"));

        verify(orderPublisher, never()).publishOrderWithConfirm(any(), any());
    }

    @Test
    void brokerUnavailableScenarioMarksOrderNotPublishedAndReturns503() throws Exception {
        String runId = UUID.randomUUID().toString();
        String journeyId = UUID.randomUUID().toString();
        String cartId = UUID.randomUUID().toString();
        CheckoutRequest req = new CheckoutRequest(runId, journeyId, cartId);

        when(responseSpec.body(PaymentAuthorizeResponse.class)).thenReturn(
                new PaymentAuthorizeResponse("auth-123", journeyId, cartId, "PAYMENT_AUTHORIZED", Instant.now().toString())
        );
        doThrow(new AmqpException("Broker unavailable"))
                .when(orderPublisher)
                .publishOrderWithConfirm(any(), any());

        mockMvc.perform(post("/internal/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, runId)
                        .header(CorrelationHeaders.JOURNEY_ID, journeyId)
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, "BROKER_UNAVAILABLE")
                        .header(CorrelationHeaders.SIMULATION_ENABLED, "true")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error_code").value("BROKER_UNAVAILABLE"));

        verify(orderRepository).updateOrderStatus(anyString(), org.mockito.ArgumentMatchers.eq("PAYMENT_AUTHORIZED_NOT_PUBLISHED"));
    }
}
