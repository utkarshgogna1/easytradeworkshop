package com.dynatrace.easytrade.creditcardorderservice.models;

import lombok.Getter;

/**
 * Lifecycle of a Bitcoin payment intent.
 *
 * <p>The request path only ever creates a {@link #PENDING} intent. Transitions to
 * {@link #CONFIRMED} or {@link #FAILED} are driven asynchronously (out of the request
 * path) once on-chain confirmation is observed — see the async worker described in
 * {@code docs/bitcoin-payment-design.md}. Keeping confirmation off the request path is
 * what lets this feature scale without blocking threads on slow blockchain confirmations.
 */
@Getter
public enum BitcoinPaymentStatus {
    PENDING("pending", "Bitcoin payment recorded and awaiting on-chain confirmation."),
    CONFIRMED("confirmed", "Bitcoin payment confirmed on-chain."),
    FAILED("failed", "Bitcoin payment failed or expired before confirmation.");

    private final String type;
    private final String description;

    BitcoinPaymentStatus(String type, String description) {
        this.type = type;
        this.description = description;
    }
}
