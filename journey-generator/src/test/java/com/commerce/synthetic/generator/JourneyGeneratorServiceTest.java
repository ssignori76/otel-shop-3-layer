package com.commerce.synthetic.generator;

import com.commerce.synthetic.api.JourneyResponse;
import com.commerce.synthetic.api.JourneyStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JourneyGeneratorServiceTest {

    @Test
    void validatesGeneratorPropertiesConstraints() {
        GeneratorProperties props = new GeneratorProperties();
        props.setIntervalSeconds(10);
        props.setInitialDelaySeconds(5);
        props.setMaxInFlight(1);
        props.setRequestTimeoutSeconds(30);
        props.validate();

        GeneratorProperties badInterval = new GeneratorProperties();
        badInterval.setIntervalSeconds(0);
        assertThatThrownBy(badInterval::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GENERATOR_INTERVAL_SECONDS");

        GeneratorProperties badInFlight = new GeneratorProperties();
        badInFlight.setMaxInFlight(2);
        assertThatThrownBy(badInFlight::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GENERATOR_MAX_IN_FLIGHT");
    }

    @Test
    void executesSingleJourneyAndTracksDelayBetweenRuns() {
        GeneratorProperties props = new GeneratorProperties();
        props.setEnabled(false);
        props.setAuthEnabled(false);
        props.setScenarioName("NORMAL");
        props.setScenarioEnabled(false);

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(JourneyStartRequest.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        String runId = UUID.randomUUID().toString();
        String journeyId1 = UUID.randomUUID().toString();
        when(responseSpec.body(JourneyResponse.class)).thenReturn(new JourneyResponse(
                runId,
                journeyId1,
                "cart-123",
                "order-456",
                "auth-789",
                "ORDER_PUBLISHED",
                "0123456789abcdef0123456789abcdef"
        ));

        JourneyGeneratorService service = new JourneyGeneratorService(props, new ObjectMapper(), restClient);
        JourneyExecutionRecord first = service.executeSingleJourney(runId, journeyId1, "NORMAL", false);

        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(first.state()).isEqualTo("ORDER_PUBLISHED");
        assertThat(first.cartId()).isEqualTo("cart-123");
        assertThat(first.orderId()).isEqualTo("order-456");

        String journeyId2 = UUID.randomUUID().toString();
        JourneyExecutionRecord second = service.executeSingleJourney(runId, journeyId2, "DB_SLOW", false);
        assertThat(second.effectiveScenario()).isEqualTo("NORMAL");
        assertThat(service.getHistory()).hasSize(2);
    }
}
