package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Request to initiate a Bitcoin payment.
 *
 * @param accountId       the account initiating the payment
 * @param amount          the fiat amount to be paid, in the currency given by {@code currency}
 * @param currency        ISO-4217 currency code of {@code amount} (e.g. "USD")
 * @param btcAddress      the destination Bitcoin address the payment is expected at
 */
public record BitcoinPaymentRequest(Integer accountId, BigDecimal amount, String currency, String btcAddress) {
    public BitcoinPaymentRequest {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(btcAddress, "btcAddress is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
    }
}
