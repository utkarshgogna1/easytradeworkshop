package com.dynatrace.easytrade.creditcardorderservice;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatus;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Bitcoin payment endpoints (first, flag-gated increment).
 *
 * <p>The whole feature is dark unless the {@code bitcoin_payment} feature flag is enabled
 * (default off). By design the request path never blocks on the blockchain: creating a
 * payment records a {@link BitcoinPaymentStatus#PENDING} intent and returns immediately
 * with {@code 202 Accepted}. Confirmation is driven asynchronously later — see
 * {@code docs/bitcoin-payment-design.md}.
 */
@RestController
@RequestMapping(value = "/v1/payments",
        produces = {"application/json", "application/xml"})
@CrossOrigin
@ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Bitcoin payment accepted and pending confirmation", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad request - check message and data for hints", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "Payment not found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "503", description = "Feature disabled", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
})
public class PaymentController {
    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    public static final String BITCOIN_PAYMENT_FLAG = "bitcoin_payment";
    public static final String FEATURE_DISABLED = "Bitcoin payments are not enabled.";
    public static final String PAYMENT_ACCEPTED = "Bitcoin payment accepted and awaiting confirmation.";
    public static final String PAYMENT_FOUND = "Bitcoin payment found.";
    public static final String PAYMENT_NOT_FOUND = "No bitcoin payment exists for the given payment id.";
    public static final String INVALID_REQUEST = "Invalid bitcoin payment request.";

    private final BitcoinPaymentStore paymentStore;
    private final OpenFeatureAPI openFeatureAPI;

    public PaymentController(BitcoinPaymentStore paymentStore, OpenFeatureAPI openFeatureAPI) {
        this.paymentStore = paymentStore;
        this.openFeatureAPI = openFeatureAPI;
    }

    @PostMapping(value = "/bitcoin", consumes = {"application/json", "application/xml"})
    @Operation(summary = "Initiate a bitcoin payment")
    public ResponseEntity<StandardResponse> createBitcoinPayment(@RequestBody BitcoinPaymentRequest request) {
        if (!isFeatureEnabled()) {
            return buildResponseEntity(HttpStatus.SERVICE_UNAVAILABLE, FEATURE_DISABLED);
        }

        if (request == null) {
            return buildResponseEntity(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
        }

        final BitcoinPayment payment;
        try {
            OffsetDateTime now = OffsetDateTime.now();
            payment = new BitcoinPayment(
                    UUID.randomUUID().toString(),
                    request.accountId(),
                    request.amount(),
                    request.currency(),
                    request.btcAddress(),
                    BitcoinPaymentStatus.PENDING,
                    now,
                    now);
        } catch (RuntimeException e) {
            // Deserialization already succeeded; this guards any residual validation issues.
            return buildResponseEntity(HttpStatus.BAD_REQUEST, INVALID_REQUEST, null, null, e.getMessage());
        }

        paymentStore.save(payment);
        logger.info("Recorded pending bitcoin payment {} for account {}", payment.paymentId(), payment.accountId());

        return buildResponseEntity(HttpStatus.ACCEPTED, PAYMENT_ACCEPTED, payment);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get the status of a bitcoin payment")
    public ResponseEntity<StandardResponse> getBitcoinPayment(@PathVariable String paymentId) {
        if (!isFeatureEnabled()) {
            return buildResponseEntity(HttpStatus.SERVICE_UNAVAILABLE, FEATURE_DISABLED);
        }

        Optional<BitcoinPayment> payment = paymentStore.findById(paymentId);
        return payment
                .map(p -> buildResponseEntity(HttpStatus.OK, PAYMENT_FOUND, p))
                .orElse(buildResponseEntity(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND));
    }

    private boolean isFeatureEnabled() {
        final Client client = openFeatureAPI.getClient();
        return client.getBooleanValue(BITCOIN_PAYMENT_FLAG, false);
    }

    private ResponseEntity<StandardResponse> buildResponseEntity(HttpStatus status, String message) {
        return buildResponseEntity(status, message, null, null, null);
    }

    private ResponseEntity<StandardResponse> buildResponseEntity(HttpStatus status, String message, Object results) {
        return buildResponseEntity(status, message, results, null, null);
    }

    private ResponseEntity<StandardResponse> buildResponseEntity(HttpStatus status, String message, Object results,
            Object data, Object error) {
        return ResponseEntity
                .status(status)
                .body(new StandardResponse(
                        status.value(),
                        message,
                        results,
                        data,
                        error));
    }
}
