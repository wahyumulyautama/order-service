package com.example.order.inventory;

import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "inventory-api")
@Path("/api/inventory")
@Produces(MediaType.APPLICATION_JSON)
public interface InventoryClient {

    @GET
    @Path("/{id}")
    Uni<ProductDto> getProduct(@PathParam("id") String id);

    // 404 → business error (not retried, not counted by CB); 5xx/429 → infrastructure error (retried).
    @ClientExceptionMapper
    static RuntimeException toException(Response response) {
        int status = response.getStatus();
        if (status == 404) {
            return new ProductNotFoundException("Product not found in InventoryService");
        }
        if (status >= 500 || status == 429) {
            return new InventoryUnavailableException("InventoryService responded with HTTP " + status);
        }
        return null;
    }
}
