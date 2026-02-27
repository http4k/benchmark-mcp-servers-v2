package com.benchmark.mcp.tools;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@Path("/")
@RegisterRestClient(configKey = "api-service")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ApiServiceClient {

    @GET
    @Path("/products/search")
    Uni<Map<String, Object>> searchProducts(
            @QueryParam("category") String category,
            @QueryParam("min_price") double minPrice,
            @QueryParam("max_price") double maxPrice,
            @QueryParam("limit") int limit);

    @GET
    @Path("/products/{id}")
    Uni<Map<String, Object>> getProduct(@PathParam("id") long id);

    @POST
    @Path("/cart/calculate")
    Uni<Map<String, Object>> calculateCart(Map<String, Object> body);
}
