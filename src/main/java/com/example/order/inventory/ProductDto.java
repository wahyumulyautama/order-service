package com.example.order.inventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Representasi produk dari InventoryService.
 * {@code stock} memakai Integer (bukan int) supaya bisa bernilai null = "tidak diketahui"
 * saat kita berada di mode degradasi.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDto(String id, String name, Integer stock, boolean available) {
}
