package com.example.order.inventory;

/**
 * Upstream sedang bermasalah (HTTP 5xx / 429). Kesalahan transient yang layak di-retry.
 */
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message) {
        super(message);
    }
}
