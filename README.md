# Order Service — Resilient Integration dengan InventoryService

Quarkus 3.15 (LTS) · Java 17 · SmallRye Fault Tolerance · REST Client Reactive · Micrometer/Prometheus

## Cara menjalankan

```bash
# Menjalankan seluruh integration test (WireMock otomatis dinyalakan)
mvn verify

# Dev mode (arahkan ke InventoryService asli / mock)
INVENTORY_BASE_URL=http://localhost:8081 mvn quarkus:dev

curl http://localhost:8080/api/orders/products/P-001/availability
curl http://localhost:8080/q/metrics      # Prometheus
curl http://localhost:8080/q/health/ready # status circuit breaker
```

Contoh respons saat degradasi:

```json
{ "product": { "id": "P-001", "name": null, "stock": null, "available": false },
  "source": "DEFAULT", "degraded": true, "asOf": "2026-11-05T12:00:00Z" }
```

## Struktur

```
src/main/java/com/example/order/
├── api/ProductAvailabilityResource.java      # endpoint GET /api/orders/products/{id}/availability
├── inventory/
│   ├── InventoryClient.java                  # REST client reactive (Uni) + mapping status HTTP -> exception
│   ├── InventoryService.java                 # Timeout, Retry, Circuit Breaker, Bulkhead, Fallback
│   ├── LastKnownGoodCache.java               # sumber data fallback (stale-but-valid)
│   ├── ProductDto.java / ProductAvailability.java
│   └── *Exception.java
└── observability/
    ├── InventoryMetrics.java                 # gauge state CB + counter fallback
    └── InventoryDependencyHealthCheck.java   # status CB di /q/health/ready (selalu UP, lihat alasan)
src/test/java/.../InventoryResilienceTest.java # integration test dengan WireMock
```

---

## Resilience Design

Urutan interceptor (luar → dalam): **Fallback → Retry → Circuit Breaker → Timeout → Bulkhead → HTTP call**.
Artinya timeout berlaku **per percobaan**, dan setiap percobaan retry **dihitung** oleh circuit breaker.

| Mekanisme | Nilai | Alasan |
|---|---|---|
| **Timeout** | 800 ms per percobaan | Lookup stok by-id seharusnya p99 < ~300 ms. 800 ms ≈ 2–3× p99: cukup longgar untuk lonjakan wajar, cukup ketat agar thread/koneksi tidak tertahan. Idealnya diturunkan dari data p99 produksi. |
| Socket timeout client | connect 500 ms, read 1000 ms | Jaring pengaman kedua, sedikit di atas `@Timeout` supaya yang memutus tetap FT (terukur di metrik). |
| **Retry** | maks 2 (total 3 percobaan), delay 200 ms + jitter 0–100 ms, `maxDuration` 2500 ms | Error transient biasanya pulih dalam ratusan ms. Jitter mencegah *thundering herd* (semua pod retry serentak). Lebih dari 2 retry hanya memperbesar beban saat upstream sedang sakit (*retry storm*). `maxDuration` menjamin user tidak menunggu > 2,5 s. |
| Retry **tidak** dilakukan untuk | 404, 4xx lain, CB open, bulkhead penuh | Error bisnis/klien tidak akan berubah hasilnya bila diulang; CB open & bulkhead penuh berarti sistem sedang melindungi diri — retry justru melawan proteksi itu. GET bersifat idempotent sehingga aman di-retry. |
| **Circuit Breaker** | window 20 percobaan, failure ratio 50%, delay 10 s, success threshold 3 | 20 sampel cukup signifikan secara statistik (tidak terbuka karena 2–3 error kebetulan) tapi tetap cepat bereaksi saat trafik tinggi. 50% = jelas bukan noise. 10 s memberi upstream waktu bernapas/restart tanpa membuat user menunggu terlalu lama. 3 sukses beruntun di HALF_OPEN mencegah *flapping*. 404 dianggap sukses (`skipOn`). |
| **Bulkhead** | 50 concurrent, antrean 100 | Membatasi berapa banyak panggilan in-flight ke Inventory, sejajar dengan `connection-pool-size=50`. Jika Inventory lambat, yang tertahan maksimal 50 request — sisa sistem tetap jalan. Hukum Little: 50 slot / 0,2 s rata-rata ≈ 250 RPS kapasitas normal. |
| **Fallback** | 2 tingkat | (1) Data *last-known-good* dari cache (TTL 10 menit) ditandai `source=CACHE, degraded=true`. (2) Bila tidak ada, default **fail-safe**: `available=false, stock=null` (`source=DEFAULT`) — lebih aman menolak sementara daripada *overselling*. Field `source`/`degraded` membuat pemanggil (checkout) bisa memutuskan, mis. menahan order sebagai "menunggu konfirmasi stok". |

**Anggaran waktu terburuk:** 3 × 800 ms + 2 × ≤300 ms ≈ 3,0 s → dipotong `maxDuration` menjadi **≤ 2,5 s**. Saat CB OPEN, respons fallback **instan** (milidetik).

Semua nilai dapat diubah per environment tanpa rebuild, contoh:
`com.example.order.inventory.InventoryService/getProductAvailability/CircuitBreaker/delay=15000`

### Skenario yang dibuktikan oleh test (`InventoryResilienceTest`)

Profile test memakai nilai lebih kecil agar cepat: timeout 500 ms, retry delay 50 ms, CB window 4, delay 1 s.

| Skenario | Test | Yang dibuktikan |
|---|---|---|
| Happy path | `returnsLiveData` | `source=LIVE`, tepat 1 panggilan upstream, CB CLOSED |
| Happy path (bisnis) | `notFoundIsBusinessError` | 404 diteruskan, tidak di-retry, CB tidak terpengaruh |
| High latency (sesaat) | `transientSlownessRecoveredByRetry` | Percobaan 1 timeout, retry sukses → LIVE < 2 s (upstream delay 3 s) |
| High latency (terus-menerus) | `persistentSlownessDegradesWithinBudget` | 3 percobaan diputus timeout, fallback DEFAULT dalam < 2,5 s (upstream delay 5 s) |
| Transient error | `transientConnectionResetRecovered` | Connection reset dipulihkan oleh retry |
| Outage | `circuitBreakerOpensAndFailsFast` | CB OPEN setelah 4 kegagalan; 5 request berikutnya < 300 ms dan **0** panggilan tambahan ke upstream |
| Outage + cache | `servesStaleCacheDuringOutage` | User tetap dapat data terakhir, `source=CACHE`, `degraded=true` |
| Pemulihan | `circuitBreakerRecoversThroughHalfOpen` | OPEN → HALF_OPEN → CLOSED setelah upstream sehat |

---

## Architecture & Production Readiness

### 1. Thread Pool & Event Loop Isolation

The core principle: no thread ever blocks waiting on InventoryService, and concurrent in-flight calls are bounded.

- **Non-blocking end-to-end.** Endpoint, REST client, and service method all return `Uni` with `@AsynchronousNonBlocking`. The event loop thread is released immediately when the outbound call is in flight — 1,000 pending requests to a slow Inventory ≠ 1,000 blocked threads. Retry and CB delays use Vert.x timers, not `Thread.sleep`.
- **Bulkhead (50 concurrent + 100 queued)** caps in-flight calls to this one dependency. When Inventory is slow, overflow goes straight to fallback; the rest of the application is unaffected.
- **Connection pool per client** (`connection-pool-size=50`) — Inventory connections cannot starve other dependencies.
- **Layered timeouts** (FT @Timeout 800 ms, socket read 1 000 ms, `maxDuration` 2 500 ms): no request ever hangs indefinitely; Uni cancellation closes the HTTP connection when a timeout fires.
- **Circuit breaker** stops request flow to a clearly unhealthy upstream, reducing load and giving it time to recover.
- **Health check stays UP when CB is OPEN.** If readiness flipped DOWN when Inventory degraded, Kubernetes would remove all OrderService pods from the load balancer — turning a partial outage into a total one. CB state is monitored via metrics and alerts, not via the probe.

### 2. Observability & Monitoring

Metrik bawaan SmallRye FT otomatis diekspos via Micrometer di `/q/metrics` (nama Prometheus, titik → underscore), ditambah metrik custom:

| Kategori | Metrik | Kegunaan / contoh alert |
|---|---|---|
| **Status CB** | `inventory_circuitbreaker_state{name}` (custom gauge 0=CLOSED, 1=HALF_OPEN, 2=OPEN) | Alert **critical** jika `== 2` > 1 menit. Panel status real-time. |
| | `ft_circuitbreaker_opened_total` | Berapa kali CB terbuka. Alert jika `increase(...[15m]) > 3` → *flapping*, threshold perlu ditinjau. |
| | `ft_circuitbreaker_state_total{state}` | Akumulasi durasi di tiap state (SLA dependency). |
| | `ft_circuitbreaker_calls_total{circuitBreakerResult=success\|failure\|circuitBreakerOpen}` | Rasio panggilan yang ditolak CB. |
| **Tingkat kegagalan** | `ft_invocations_total{result, fallback}` | Error rate & fallback rate keseluruhan. Alert jika `fallback="applied"` > 5% dalam 5 menit. |
| | `inventory_fallback_total{source=cache\|default}` (custom) | Dampak ke user: `default` artinya user benar-benar tidak dapat data stok. |
| | `http_client_requests_seconds_count{uri, status, outcome}` | Distribusi status HTTP dari Inventory (5xx vs 4xx vs timeout). |
| **Latensi** | `http_client_requests_seconds` (histogram) | p50/p95/p99 latensi Inventory — dasar untuk men-tuning nilai `@Timeout`. |
| | `ft_timeout_calls_total{timedOut=true}` | Berapa percobaan yang diputus timeout. |
| | `http_server_requests_seconds{uri="/api/orders/products/{id}/availability"}` | Latensi yang dirasakan user (harus tetap ≤ 2,5 s). |
| **Retry** | `ft_retry_retries_total`, `ft_retry_calls_total{retried, retryResult}` | Retry yang naik tajam = upstream mulai tidak stabil (sinyal dini sebelum CB terbuka). |
| **Saturasi** | `ft_bulkhead_executionsRunning`, `ft_bulkhead_executionsWaiting`, `ft_bulkhead_calls_total{bulkheadResult=rejected}` | Bulkhead penuh/rejected → kapasitas perlu dinaikkan atau upstream terlalu lambat. |
| | `vertx_*` / event loop lag, JVM threads | Memastikan tidak ada blocking di event loop. |

Pelengkap: **log terstruktur** setiap perubahan state CB dan setiap fallback (sudah ada di kode), **tracing** OpenTelemetry (`quarkus-opentelemetry`) agar retry/timeout terlihat sebagai span di bawah request user, dan **dashboard** yang menyandingkan latensi upstream, error rate, state CB, dan fallback rate dalam satu layar.
