package com.enterprise.oms.fulfillment.service;

import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides whether one fulfillment attempt fails. Deterministic hook: shipping city {@value #FAIL_CITY} always fails
 * (every attempt); otherwise {@code oms.fulfillment.failure-rate} gives a random failure probability.
 */
@Component
public class FailureSimulator {

    public static final String FAIL_CITY = "FAILTOWN";

    private final FulfillmentProperties props;
    private final DoubleSupplier random;

    @Autowired
    public FailureSimulator(FulfillmentProperties props) {
        this(props, () -> ThreadLocalRandom.current().nextDouble());
    }

    FailureSimulator(FulfillmentProperties props, DoubleSupplier random) {
        this.props = props;
        this.random = random;
    }

    /** @return failure reason, or empty when the attempt succeeds */
    public Optional<String> check(String shipCity) {
        if (shipCity != null && FAIL_CITY.equalsIgnoreCase(shipCity.trim())) {
            return Optional.of("Shipping address not serviceable: city " + FAIL_CITY);
        }
        double rate = props.getFailureRate();
        if (rate > 0.0 && random.getAsDouble() < rate) {
            return Optional.of("Simulated random fulfillment failure");
        }
        return Optional.empty();
    }
}
