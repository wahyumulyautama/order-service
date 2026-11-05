package com.example.order;

import com.example.order.inventory.InventoryService;
import com.example.order.inventory.LastKnownGoodCache;
import com.example.order.support.InjectWireMock;
import com.example.order.support.WireMockInventoryResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test strategi resilience. Konfigurasi FT di profile test
 * (lihat application.properties, prefix %test):
 *   Timeout 500 ms | Retry maks 2x, jeda 50 ms | CB window 4, ratio 0.5, delay 1 s, successThreshold 2
 */
@QuarkusTest
@QuarkusTestResource(WireMockInventoryResource.class)
class InventoryResilienceTest {

    private static final String PRODUCT_ID = "P-001";
    private static final String UPSTREAM_PATH = "/api/inventory/" + PRODUCT_ID;
    private static final String ENDPOINT = "/api/orders/products/{id}/availability";
    private static final String PRODUCT_JSON =
            "{\"id\":\"P-001\",\"name\":\"Kopi Arabika 250g\",\"stock\":42,\"available\":true}";

    /** Total percobaan = 1 panggilan awal + 2 retry. */
    private static final int ATTEMPTS_PER_CALL = 3;

    private static volatile boolean warmUpDone = false;

    @InjectWireMock
    WireMockServer wireMock;

    @Inject
    CircuitBreakerMaintenance circuitBreaker;

    @Inject
    LastKnownGoodCache cache;

    @BeforeEach
    void resetState() {
        if (!warmUpDone) {
            // Pre-warm the Vert.x connection pool before the first test.
            // On machines with AV inspection (e.g. McAfee), the first connection to a new dynamic
            // port can take >500 ms, triggering a spurious FT timeout+retry. Done in @BeforeEach
            // (not @BeforeAll) because Quarkus sets RestAssured.port in beforeEach().
            warmUpDone = true;
            wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH)).willReturn(okJson()));
            given().when().get(ENDPOINT, PRODUCT_ID);
        }
        wireMock.resetAll();
        circuitBreaker.resetAll();
        cache.clear();
    }

    // =====================================================================
    // 1. HAPPY PATH
    // =====================================================================
    @Test
    @DisplayName("Mengembalikan data LIVE dengan tepat satu panggilan ke upstream")
    void returnsLiveData() {
        stubOk();

        callAvailability()
                .then()
                .statusCode(200)
                .body("source", equalTo("LIVE"))
                .body("degraded", equalTo(false))
                .body("product.id", equalTo(PRODUCT_ID))
                .body("product.name", equalTo("Kopi Arabika 250g"))
                .body("product.stock", equalTo(42))
                .body("product.available", equalTo(true));

        wireMock.verify(1, getRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        assertEquals(CircuitBreakerState.CLOSED, cbState());
    }

    @Test
    @DisplayName("404 dari upstream diteruskan sebagai 404, tidak di-retry, tidak memicu CB")
    void notFoundIsBusinessError() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(404)));

        callAvailability()
                .then()
                .statusCode(404)
                .body("code", equalTo("PRODUCT_NOT_FOUND"));

        wireMock.verify(1, getRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        assertEquals(CircuitBreakerState.CLOSED, cbState());
    }

    // =====================================================================
    // 2. HIGH LATENCY
    // =====================================================================
    @Test
    @DisplayName("Lambat sesaat: percobaan pertama timeout, retry berhasil -> LIVE")
    void transientSlownessRecoveredByRetry() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH))
                .inScenario("slow-then-fast")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson().withFixedDelay(3_000))
                .willSetStateTo("recovered"));
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH))
                .inScenario("slow-then-fast")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson()));

        long start = System.currentTimeMillis();
        callAvailability()
                .then()
                .statusCode(200)
                .body("source", equalTo("LIVE"))
                .body("product.stock", equalTo(42));
        long elapsed = System.currentTimeMillis() - start;

        // ~500 ms (timeout) + 50 ms (jeda) + respons cepat, jauh di bawah delay 3 s upstream
        assertTrue(elapsed < 2_000, "Seharusnya tidak menunggu upstream 3 s, ternyata " + elapsed + " ms");
        awaitUpstreamCalls(2);
    }

    @Test
    @DisplayName("Selalu lambat: tiap percobaan diputus timeout, user dapat fallback dalam batas waktu")
    void persistentSlownessDegradesWithinBudget() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH)).willReturn(okJson().withFixedDelay(5_000)));

        long start = System.currentTimeMillis();
        callAvailability()
                .then()
                .statusCode(200)
                .body("source", equalTo("DEFAULT"))
                .body("degraded", equalTo(true))
                .body("product.id", equalTo(PRODUCT_ID))
                .body("product.available", equalTo(false))
                .body("product.stock", nullValue());
        long elapsed = System.currentTimeMillis() - start;

        // Anggaran: 3 x 500 ms + 2 x 50 ms ≈ 1.6 s. Tanpa resilience: 5 s+ per request.
        assertTrue(elapsed < 2_500, "Respons harus dalam anggaran waktu, ternyata " + elapsed + " ms");
        awaitUpstreamCalls(ATTEMPTS_PER_CALL);
    }

    // =====================================================================
    // 3. SERVICE OUTAGE
    // =====================================================================
    @Test
    @DisplayName("Gangguan koneksi sesaat (connection reset) dipulihkan oleh retry")
    void transientConnectionResetRecovered() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("ok"));
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("ok")
                .willReturn(okJson()));

        callAvailability()
                .then()
                .statusCode(200)
                .body("source", equalTo("LIVE"));

        wireMock.verify(2, getRequestedFor(urlEqualTo(UPSTREAM_PATH)));
    }

    @Test
    @DisplayName("Outage: circuit breaker OPEN lalu fail-fast tanpa membebani upstream")
    void circuitBreakerOpensAndFailsFast() {
        stubServiceUnavailable();

        // Panggilan #1: 3 percobaan gagal (window CB belum penuh, CB masih CLOSED) -> fallback
        callAvailability().then().statusCode(200).body("source", equalTo("DEFAULT"));
        // Panggilan #2: percobaan ke-4 gagal -> 4/4 gagal >= 50% -> CB OPEN.
        // Retry berikutnya kena CircuitBreakerOpenException -> abort -> fallback.
        callAvailability().then().statusCode(200).body("source", equalTo("DEFAULT"));

        assertEquals(CircuitBreakerState.OPEN, cbState());
        awaitUpstreamCalls(4);

        // Selama OPEN: semua request dilayani fallback secara instan, upstream TIDAK disentuh.
        for (int i = 0; i < 5; i++) {
            long start = System.currentTimeMillis();
            callAvailability().then().statusCode(200).body("source", equalTo("DEFAULT"));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 300, "Fail-fast seharusnya instan, ternyata " + elapsed + " ms");
        }
        wireMock.verify(4, getRequestedFor(urlEqualTo(UPSTREAM_PATH)));
    }

    @Test
    @DisplayName("Outage dengan data lama di cache: user tetap dapat data, ditandai stale/degraded")
    void servesStaleCacheDuringOutage() {
        stubOk();
        callAvailability().then().statusCode(200).body("source", equalTo("LIVE"));

        wireMock.resetMappings();
        stubServiceUnavailable();

        callAvailability()
                .then()
                .statusCode(200)
                .body("source", equalTo("CACHE"))
                .body("degraded", equalTo(true))
                .body("product.name", equalTo("Kopi Arabika 250g"))
                .body("product.stock", equalTo(42));
    }

    @Test
    @DisplayName("Pemulihan: OPEN -> HALF_OPEN -> CLOSED saat upstream kembali sehat")
    void circuitBreakerRecoversThroughHalfOpen() throws InterruptedException {
        stubServiceUnavailable();
        callAvailability();
        callAvailability();
        assertEquals(CircuitBreakerState.OPEN, cbState());

        // Upstream pulih, tunggu melewati delay CB (test: 1 s)
        wireMock.resetMappings();
        stubOk();
        Thread.sleep(1_200);

        // Trial #1 di HALF_OPEN sukses (1/2), trial #2 sukses (2/2) -> CLOSED
        callAvailability().then().statusCode(200).body("source", equalTo("LIVE"));
        callAvailability().then().statusCode(200).body("source", equalTo("LIVE"));

        assertEquals(CircuitBreakerState.CLOSED, cbState());
    }

    // =====================================================================
    // Helpers
    // =====================================================================
    private Response callAvailability() {
        return given().when().get(ENDPOINT, PRODUCT_ID);
    }

    private CircuitBreakerState cbState() {
        return circuitBreaker.currentState(InventoryService.CIRCUIT_BREAKER_NAME);
    }

    private void stubOk() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH)).willReturn(okJson()));
    }

    private void stubServiceUnavailable() {
        wireMock.stubFor(get(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(503)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(PRODUCT_JSON);
    }

    /**
     * Request yang di-delay WireMock bisa saja baru tercatat di journal setelah
     * diproses, jadi kita poll sebentar sebelum assert jumlah panggilan.
     */
    private void awaitUpstreamCalls(int expected) {
        long deadline = System.currentTimeMillis() + 6_000;
        int count = upstreamCallCount();
        while (count < expected && System.currentTimeMillis() < deadline) {
            sleepQuietly(50);
            count = upstreamCallCount();
        }
        assertEquals(expected, count, "Jumlah panggilan ke InventoryService");
    }

    private int upstreamCallCount() {
        return wireMock.countRequestsMatching(getRequestedFor(urlEqualTo(UPSTREAM_PATH)).build()).getCount();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
