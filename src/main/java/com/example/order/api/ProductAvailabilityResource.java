package com.example.order.api;

import com.example.order.inventory.InventoryService;
import com.example.order.inventory.ProductAvailability;
import com.example.order.inventory.ProductNotFoundException;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

@Path("/api/orders/products")
@Produces(MediaType.APPLICATION_JSON)
public class ProductAvailabilityResource {

    private final InventoryService inventoryService;

    ProductAvailabilityResource(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GET
    @Path("/{id}/availability")
    public Uni<ProductAvailability> availability(@PathParam("id") String productId) {
        return inventoryService.getProductAvailability(productId);
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapNotFound(ProductNotFoundException e) {
        return RestResponse.status(Response.Status.NOT_FOUND,
                new ErrorResponse("PRODUCT_NOT_FOUND", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {
    }
}
