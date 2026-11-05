package com.example.order.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LastKnownGoodCache {

    public record CachedProduct(ProductDto product, Instant cachedAt) {
    }

    private final Map<String, CachedProduct> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;

    LastKnownGoodCache(@ConfigProperty(name = "inventory.cache.ttl", defaultValue = "PT10M") Duration ttl,
                       @ConfigProperty(name = "inventory.cache.max-entries", defaultValue = "10000") int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public void put(String productId, ProductDto product) {
        if (productId == null || product == null) {
            return;
        }
        if (store.size() >= maxEntries && !store.containsKey(productId)) {
            return;
        }
        store.put(productId, new CachedProduct(product, Instant.now()));
    }

    public Optional<CachedProduct> get(String productId) {
        CachedProduct cached = store.get(productId);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.cachedAt().plus(ttl).isBefore(Instant.now())) {
            store.remove(productId, cached);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    public void clear() {
        store.clear();
    }
}
