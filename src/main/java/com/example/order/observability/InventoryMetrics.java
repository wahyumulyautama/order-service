package com.example.order.observability;

import com.example.order.inventory.InventoryService;
import com.example.order.inventory.ProductAvailability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom metrics complementing SmallRye FT built-ins:
 * - {@code inventory_circuitbreaker_state} gauge (0=CLOSED, 1=HALF_OPEN, 2=OPEN) — alert-friendly
 * - {@code inventory_fallback_total{source}} counter per fallback source (CACHE / DEFAULT)
 */
@ApplicationScoped
public class InventoryMetrics {

    private static final Logger LOG = Logger.getLogger(InventoryMetrics.class);

    private final MeterRegistry registry;
    private final AtomicInteger circuitBreakerState = new AtomicInteger(0);

    InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void onStart(@Observes StartupEvent event, CircuitBreakerMaintenance maintenance) {
        registry.gauge("inventory.circuitbreaker.state",
                Tags.of("name", InventoryService.CIRCUIT_BREAKER_NAME), circuitBreakerState);
        updateState(maintenance.currentState(InventoryService.CIRCUIT_BREAKER_NAME));
        maintenance.onStateChange(InventoryService.CIRCUIT_BREAKER_NAME, this::updateState);
    }

    private void updateState(CircuitBreakerState state) {
        int value = switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            default -> -1;
        };
        int previous = circuitBreakerState.getAndSet(value);
        if (previous != value) {
            LOG.warnf("Circuit breaker '%s' changed state -> %s", InventoryService.CIRCUIT_BREAKER_NAME, state);
        }
    }

    public void recordFallback(ProductAvailability.Source source) {
        registry.counter("inventory.fallback", "source", source.name().toLowerCase()).increment();
    }
}
