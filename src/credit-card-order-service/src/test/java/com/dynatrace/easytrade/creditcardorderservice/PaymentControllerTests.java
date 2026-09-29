package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatus;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class PaymentControllerTests {
    @Mock
    OpenFeatureAPI openFeatureAPI;
    @Mock
    Client client;

    private final BitcoinPaymentRequest REQUEST =
            new BitcoinPaymentRequest(13, new BigDecimal("0.05"), "USD", "bc1qexampleaddress0000000000000000000000");

    private void enableFlag(boolean enabled) {
        Mockito.when(openFeatureAPI.getClient()).thenReturn(client);
        Mockito.when(client.getBooleanValue(eq(PaymentController.BITCOIN_PAYMENT_FLAG), anyBoolean()))
                .thenReturn(enabled);
    }

    @Test
    void createReturnsServiceUnavailableWhenFlagDisabled() {
        enableFlag(false);
        PaymentController controller = new PaymentController(new InMemoryBitcoinPaymentStore(), openFeatureAPI);

        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(REQUEST);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), body.statusCode());
        assertEquals(PaymentController.FEATURE_DISABLED, body.message());
    }

    @Test
    void createRecordsPendingPaymentAndReturnsAccepted() {
        enableFlag(true);
        PaymentController controller = new PaymentController(new InMemoryBitcoinPaymentStore(), openFeatureAPI);

        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(REQUEST);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.ACCEPTED.value(), body.statusCode());
        assertEquals(PaymentController.PAYMENT_ACCEPTED, body.message());
        BitcoinPayment payment = assertInstanceOf(BitcoinPayment.class, body.results());
        assertEquals(BitcoinPaymentStatus.PENDING, payment.status());
        assertNotNull(payment.paymentId());
    }

    @Test
    void createWithNullRequestReturnsBadRequest() {
        enableFlag(true);
        PaymentController controller = new PaymentController(new InMemoryBitcoinPaymentStore(), openFeatureAPI);

        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(null);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.BAD_REQUEST.value(), body.statusCode());
        assertEquals(PaymentController.INVALID_REQUEST, body.message());
    }

    @Test
    void getReturnsPaymentAfterCreate() {
        enableFlag(true);
        InMemoryBitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        PaymentController controller = new PaymentController(store, openFeatureAPI);

        ResponseEntity<StandardResponse> created = controller.createBitcoinPayment(REQUEST);
        BitcoinPayment createdPayment = (BitcoinPayment) created.getBody().results();

        ResponseEntity<StandardResponse> response = controller.getBitcoinPayment(createdPayment.paymentId());
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.OK.value(), body.statusCode());
        assertEquals(PaymentController.PAYMENT_FOUND, body.message());
        BitcoinPayment found = assertInstanceOf(BitcoinPayment.class, body.results());
        assertEquals(createdPayment.paymentId(), found.paymentId());
    }

    @Test
    void getReturnsNotFoundForUnknownId() {
        enableFlag(true);
        PaymentController controller = new PaymentController(new InMemoryBitcoinPaymentStore(), openFeatureAPI);

        ResponseEntity<StandardResponse> response = controller.getBitcoinPayment("does-not-exist");
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.NOT_FOUND.value(), body.statusCode());
        assertEquals(PaymentController.PAYMENT_NOT_FOUND, body.message());
    }
}
