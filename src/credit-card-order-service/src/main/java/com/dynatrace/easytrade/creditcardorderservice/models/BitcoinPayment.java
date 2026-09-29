package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A Bitcoin payment intent and its current state.
 *
 * <p>Returned to clients from both the create ({@code POST /v1/payments/bitcoin}) and
 * lookup ({@code GET /v1/payments/{paymentId}}) endpoints.
 */
public record BitcoinPayment(
        String paymentId,
        Integer accountId,
        BigDecimal amount,
        String currency,
        String btcAddress,
        BitcoinPaymentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Returns a copy of this payment with a new status and a refreshed {@code updatedAt}. */
    public BitcoinPayment withStatus(BitcoinPaymentStatus newStatus) {
        return new BitcoinPayment(
                paymentId, accountId, amount, currency, btcAddress, newStatus, createdAt, OffsetDateTime.now());
    }
}
