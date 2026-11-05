package com.example.order.inventory;

import java.time.Instant;

/**
 * Respons ke konsumen OrderService. Selain data produk, kita SELALU jujur soal
 * asal data ({@code source}) dan apakah sedang degradasi, supaya pemanggil
 * (misal checkout) bisa memutuskan: lanjut, tahan, atau minta konfirmasi ulang.
 */
public record ProductAvailability(ProductDto product, Source source, boolean degraded, Instant asOf) {

    public enum Source {
        /** Data segar dari InventoryService. */
        LIVE,
        /** Data terakhir yang valid (stale) dari cache lokal. */
        CACHE,
        /** Tidak ada data sama sekali: default aman (fail-safe). */
        DEFAULT
    }

    public static ProductAvailability live(ProductDto product) {
        return new ProductAvailability(product, Source.LIVE, false, Instant.now());
    }

    public static ProductAvailability fromCache(LastKnownGoodCache.CachedProduct cached) {
        return new ProductAvailability(cached.product(), Source.CACHE, true, cached.cachedAt());
    }

    /**
     * Fail-safe untuk inventory: lebih baik bilang "tidak tersedia" daripada
     * menjual barang yang stoknya tidak bisa dipastikan (overselling).
     */
    public static ProductAvailability unavailable(String productId) {
        return new ProductAvailability(new ProductDto(productId, null, null, false),
                Source.DEFAULT, true, Instant.now());
    }
}
