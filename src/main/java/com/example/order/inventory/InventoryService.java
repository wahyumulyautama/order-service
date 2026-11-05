package com.example.order.inventory;

import com.example.order.observability.InventoryMetrics;
import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Interceptor execution order (outer → inner) in SmallRye FT:
 * <pre>
 *  Fallback → Retry → CircuitBreaker → Timeout → Bulkhead → HTTP call
 * </pre>
 * Timeout is per-attempt; Retry wraps the whole inner stack.
 */
@ApplicationScoped
public class InventoryService {

    public static final String CIRCUIT_BREAKER_NAME = "inventory-service";
    private static final Logger LOG = Logger.getLogger(InventoryService.class);

    private final InventoryClient client;
    private final LastKnownGoodCache cache;
    private final InventoryMetrics metrics;

    InventoryService(@RestClient InventoryClient client, LastKnownGoodCache cache, InventoryMetrics metrics) {
        this.client = client;
        this.cache = cache;
        this.metrics = metrics;
    }

    @AsynchronousNonBlocking
    @Bulkhead(value = 50, waitingTaskQueue = 100)
    @Timeout(800)
    @CircuitBreaker(
            requestVolumeThreshold = 20,
            failureRatio = 0.5,
            delay = 10_000,
            successThreshold = 3,
            skipOn = ProductNotFoundException.class)
    @CircuitBreakerName(CIRCUIT_BREAKER_NAME)
    @Retry(
            maxRetries = 2,
            delay = 200,
            jitter = 100,
            maxDuration = 2_500,
            abortOn = {
                    ProductNotFoundException.class,     // error bisnis, retry percuma
                    WebApplicationException.class,      // 4xx lain (400/401/403): request kita yang salah
                    CircuitBreakerOpenException.class,  // CB sudah memutuskan "jangan panggil"
                    BulkheadException.class             // sedang overload, retry hanya memperparah
            })
    @Fallback(fallbackMethod = "fallbackAvailability", skipOn = ProductNotFoundException.class)
    public Uni<ProductAvailability> getProductAvailability(String productId) {
        return client.getProduct(productId)
                .invoke(product -> cache.put(productId, product))
                .map(ProductAvailability::live);
    }

    Uni<ProductAvailability> fallbackAvailability(String productId) {
        return Uni.createFrom().item(() -> cache.get(productId)
                .map(cached -> {
                    LOG.warnf("InventoryService degraded: serving cached data for product %s (cached at %s)",
                            productId, cached.cachedAt());
                    metrics.recordFallback(ProductAvailability.Source.CACHE);
                    return ProductAvailability.fromCache(cached);
                })
                .orElseGet(() -> {
                    LOG.warnf("InventoryService degraded: no cached data for product %s, returning safe default",
                            productId);
                    metrics.recordFallback(ProductAvailability.Source.DEFAULT);
                    return ProductAvailability.unavailable(productId);
                }));
    }
}
