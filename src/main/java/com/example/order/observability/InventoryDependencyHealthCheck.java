package com.example.order.observability;

import com.example.order.inventory.InventoryService;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Reports circuit breaker state at /q/health/ready.
 *
 * Always returns UP intentionally: if readiness flipped DOWN when Inventory is degraded,
 * Kubernetes would pull all OrderService pods from the load balancer even though the service
 * is still operational (fallback active) — turning a partial outage into a total one.
 * CB state is monitored via metrics and alerts, not via the readiness probe.
 */
@Readiness
@ApplicationScoped
public class InventoryDependencyHealthCheck implements HealthCheck {

    private final CircuitBreakerMaintenance maintenance;

    InventoryDependencyHealthCheck(CircuitBreakerMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public HealthCheckResponse call() {
        CircuitBreakerState state = maintenance.currentState(InventoryService.CIRCUIT_BREAKER_NAME);
        return HealthCheckResponse.named("inventory-service-dependency")
                .up()
                .withData("circuitBreaker", state.name())
                .withData("degraded", state != CircuitBreakerState.CLOSED)
                .build();
    }
}
