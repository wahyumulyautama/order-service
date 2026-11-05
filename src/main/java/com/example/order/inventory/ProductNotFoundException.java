package com.example.order.inventory;

/**
 * Produk memang tidak ada (HTTP 404 dari upstream).
 * Ini kesalahan BISNIS, bukan kesalahan infrastruktur: tidak di-retry,
 * tidak dihitung sebagai kegagalan circuit breaker, dan tidak di-fallback.
 */
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String message) {
        super(message);
    }
}
