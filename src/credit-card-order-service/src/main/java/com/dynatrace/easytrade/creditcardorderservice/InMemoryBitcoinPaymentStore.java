package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BitcoinPaymentStore} for the first, flag-gated increment of the
 * Bitcoin payment feature.
 *
 * <p>Intentionally simple and non-durable: intents do not survive a restart and are not
 * shared across replicas. This is acceptable only while the feature is dark (the
 * {@code bitcoin_payment} flag defaults off). The target design replaces this with a
 * pooled, durable store and an async confirmation worker — see
 * {@code docs/bitcoin-payment-design.md}.
 */
@Component
public class InMemoryBitcoinPaymentStore implements BitcoinPaymentStore {

    private final ConcurrentHashMap<String, BitcoinPayment> payments = new ConcurrentHashMap<>();

    @Override
    public void save(BitcoinPayment payment) {
        payments.put(payment.paymentId(), payment);
    }

    @Override
    public Optional<BitcoinPayment> findById(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }
}
