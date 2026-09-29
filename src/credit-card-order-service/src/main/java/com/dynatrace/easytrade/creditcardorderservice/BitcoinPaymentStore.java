package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;

import java.util.Optional;

/**
 * Storage abstraction for Bitcoin payment intents.
 *
 * <p>This is the seam the target architecture plugs into: the first increment ships an
 * in-memory implementation ({@link InMemoryBitcoinPaymentStore}) to avoid a DB schema
 * change while the feature is dark (flag off by default). A durable, pooled JDBC-backed
 * implementation and the async confirmation worker replace it later without changing the
 * controller — see {@code docs/bitcoin-payment-design.md}.
 */
public interface BitcoinPaymentStore {

    /** Persists a new payment intent. */
    void save(BitcoinPayment payment);

    /** Looks up a payment intent by its id. */
    Optional<BitcoinPayment> findById(String paymentId);
}
