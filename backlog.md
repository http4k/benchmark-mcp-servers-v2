# Backlog — Melhorias de Fairness do Benchmark

Análise completa das assimetrias de implementação identificadas após revisão detalhada do código de todos os 15 servidores.

---

## Mapa de I/O Paralelo por Servidor

| Servidor | search_products | get_user_cart (2ª etapa) | checkout |
|---|---|---|---|
| **java** | ❌ sequencial | ❌ sequencial | ❌ sequencial |
| **java-vt** | ❌ sequencial | ❌ sequencial | ❌ sequencial |
| **java-webflux** | ✅ `Mono.zip` | ✅ `Mono.zip` | ✅ `Mono.zip` (4 ops) |
| **quarkus** | ✅ `Uni.combine` | ✅ `Uni.combine` | ✅ `Uni.combine` (4 ops) |
| **micronaut** | ✅ fire async + join | ✅ fire async + join | ✅ fire async + join |
| **go** | ✅ goroutines | ✅ goroutines | ✅ goroutines |
| **rust** | ✅ `tokio::spawn` | ❌ sequencial no Redis | ✅ `tokio::join!` + **pipeline** ⚠️ |
| **python** | ✅ `asyncio.gather` | ✅ `asyncio.gather` | ✅ `asyncio.gather` |
| **nodejs/bun** | ✅ `Promise.all` | ✅ `Promise.all` | ✅ `Promise.all` |

> ⚠️ Rust usa Redis pipeline no `checkout` (1 round-trip), os demais fazem 3 comandos separados (3 round-trips).

---

## 🔴 Nível 1 — Essencial

> Distorcem diretamente os resultados. Precisam ser corrigidos para que o benchmark reflita as capacidades reais de cada tecnologia.

---

### E1 · Fix do connection pool HTTP do Go

**Arquivo:** `go-server/main.go`  
**Esforço:** Mínimo (1 linha)  
**Impacto nos rankings:** 🔴 Alto — Go provavelmente sobe 5+ posições

**Problema:**  
O `http.Client` padrão do Go usa `MaxIdleConnsPerHost = 2`. Com 50 VUs concorrentes todos batendo no mesmo host (`mcp-api-service`), quase toda conexão é descartada e recriada a cada request (TCP handshake por request). O servidor Rust configura explicitamente `pool_max_idle_per_host(50)`.

Isso provavelmente explica por que Go (~1930 RPS) fica atrás de Java, Quarkus, Micronaut e Rust — contraintuitivo para uma linguagem compilada com runtime leve.

**Fix:**
```go
httpClient = &http.Client{
    Timeout: 10 * time.Second,
    Transport: &http.Transport{
        MaxIdleConnsPerHost: 100,
        MaxIdleConns:        200,
    },
}
```

---

### E2 · Uniformizar escrita Redis no `checkout` — pipeline para todos

**Arquivos:** `java-server/`, `java-vt-server/`  
**Esforço:** Baixo  
**Impacto nos rankings:** 🟠 Médio — reduz ~2-3ms de latência no checkout

**Problema:**  
Rust faz `INCR + RPUSH + ZADD` em **1 round-trip** (pipeline Redis). Todos os outros fazem **3 round-trips** separados. Numa rede Docker, cada RTT extra custa ~0.3–1ms. No p99 isso acumula.

- `java` e `java-vt`: 4 operações sequenciais (HTTP → INCR → RPUSH → ZADD)
- `python`, `nodejs`: 3 RTTs paralelos via `asyncio.gather` / `Promise.all` (aceitável)
- `micronaut`: dispara 3 futures async antes do HTTP (efetivamente paralelo, aceitável)

**Fix para java e java-vt** — usar `RedisTemplate.executePipelined()`:
```java
redis.executePipelined((RedisCallback<Object>) conn -> {
    conn.stringCommands().incr(rateKey.getBytes());
    conn.listCommands().rPush(histKey.getBytes(), orderEntry.getBytes());
    conn.zSetCommands().zIncrBy("bench:popular".getBytes(), 1.0, ("product:" + productId).getBytes());
    return null;
});
```

---

### E3 · Paralelizar I/O nos servidores `java` e `java-vt`

**Arquivos:** `java-server/`, `java-vt-server/`  
**Esforço:** Médio  
**Impacto nos rankings:** 🔴 Alto — pode reduzir latência de ~31ms para ~20ms

**Problema:**  
`java` e `java-vt` são os únicos servidores com I/O completamente sequencial nos 3 tools. Não é uma limitação do Spring Boot — `java-webflux` demonstra que Java com paralelismo explícito fica nos top performers.

> **Decisão de design a tomar:**  
> O benchmark pode estar intencionalmente comparando 3 modelos de concorrência Java:
> - `java` = blocking sequencial (padrão sem otimização)
> - `java-vt` = mesmo código, virtual threads aumentam concorrência entre requests
> - `java-webflux` = reactive, paralelismo explícito por request
>
> **Se essa for a intenção:** manter o código sequencial e documentar claramente no README.  
> **Se a intenção for comparar frameworks no seu melhor:** paralelizar conforme abaixo.

**Fix** — usar `CompletableFuture` para paralelizar:
```java
// search_products
var searchFuture = CompletableFuture.supplyAsync(() ->
    restClient.get().uri("/products/search?...").retrieve().body(Map.class));
var popularFuture = CompletableFuture.supplyAsync(() ->
    redis.opsForZSet().reverseRange("bench:popular", 0, 9));
CompletableFuture.allOf(searchFuture, popularFuture).join();
```

---

### E4 · Variar `user_id` por VU no k6

**Arquivo:** `benchmark/benchmark.js`  
**Esforço:** Mínimo (1 linha)  
**Impacto nos rankings:** 🟠 Médio — elimina hotspot artificial no Redis

**Problema:**  
Todas as 50 VUs usam `user-00042` simultaneamente. O Redis é single-threaded: 50 operações concorrentes no mesmo `bench:cart:user-00042` serializam artificialmente. Além disso, o `checkout` faz `RPUSH` nessa lista ~100k+ vezes por run, fazendo-a crescer de ~10 para ~100k entradas durante o teste.

**Fix:**
```javascript
// Antes
args: { user_id: 'user-00042' }

// Depois — distribui entre 1000 usuários pré-semeados
args: { user_id: `user-${String(__VU % 1000).padStart(5, '0')}` }
```

O Redis já tem 10k carts e 1k históricos semeados, então os VUs 0–999 terão dados reais disponíveis.

---

## 🟡 Nível 2 — Importante

> Melhorias metodológicas que aumentam a confiabilidade e reprodutibilidade dos resultados. Não urgentes, mas valem a implementação.

---

### I1 · Reset do estado do Redis entre servidores

**Arquivo:** `benchmark/run_benchmark.sh`  
**Esforço:** Baixo (5 linhas bash)  
**Impacto:** 🟡 Elimina viés de ordem entre servidores

**Problema:**  
Durante um run completo (~15 servidores × ~100k checkouts cada), listas como `bench:history:user-00042` crescem de 1k para ~1.5 milhão de entradas. Scores da `bench:popular` ZSET ficam cada vez mais distorcidos. Servidores testados depois do 12º operam contra um Redis diferente dos primeiros.

**Fix** — adicionar antes de cada `benchmark_server()`:
```bash
# Opção 1: flush completo + re-seed (~60s por servidor)
docker compose exec -T mcp-redis redis-cli FLUSHDB
docker compose run --rm redis-seeder

# Opção 2: trim cirúrgico (mais rápido, ~5s)
docker compose exec -T mcp-redis redis-cli \
    EVAL "for i=0,9999 do redis.call('LTRIM','bench:history:user-'..string.format('%05d',i),0,999) end" 0
```

---

### I2 · Uniformizar TCP_NODELAY em todos os servidores

**Arquivos:** `java-server/`, `java-vt-server/`, `java-webflux-server/`, `python-server/`, `nodejs-server/`  
**Esforço:** Baixo  
**Impacto:** 🟡 Pequeno (~0.5–2ms), mas assimétrico

**Problema:**  
Rust configura explicitamente `tcp_nodelay(true)` no `reqwest::Client`. Go usa o padrão do SO (TCP_NODELAY já ativado por padrão no `net/http`). Java, Python e Node.js dependem das libs e pode variar.

**Fix por servidor:**
- **Java WebClient/RestClient:** configurar `HttpClient` com `option(ChannelOption.TCP_NODELAY, true)`
- **Python httpx:** `httpx.AsyncHTTPTransport(socket_options=[(IPPROTO_TCP, TCP_NODELAY, 1)])`
- **Node.js:** `ioredis` já ativa por padrão; `fetch()` nativo depende do runtime

---

### I3 · Documentar / padronizar número de workers do Node.js

**Arquivo:** `docker-compose.yml`, `README.md`  
**Esforço:** Mínimo  
**Impacto:** 🟡 Transparência dos resultados

**Problema:**  
`nodejs` e `bun` rodam com `WEB_CONCURRENCY=4` (4 processos cluster), enquanto todos os outros servidores são single-process. Isso é correto para Node.js em produção (single-threaded por design), mas cria uma diferença arquitetural não documentada nos resultados.

**Opções:**
- **Opção A (recomendada):** Documentar no README e adicionar nota no `summary.json`
- **Opção B:** Criar variantes `nodejs-single` (1 worker) e `nodejs-cluster` (4 workers) para separar o efeito
- **Opção C:** Configurar todos os servidores com paralelismo explícito equivalente (Go `GOMAXPROCS`, Java thread pool sizing documentado)

---

### I4 · Warmup mais robusto para servidores JVM

**Arquivos:** `benchmark/run_benchmark.sh`, `benchmark/benchmark.js`  
**Esforço:** Baixo  
**Impacto:** 🟡 JVM pode atingir JIT peak mais cedo

**Problema:**  
A JVM atinge otimização JIT completa depois de ~50–100k invocações do mesmo método. 10 curl requests de `initialize` + 30s de exclusão no k6 pode ser insuficiente para Spring Boot pesado. O warmup atual não chama os tools reais (search, cart, checkout), apenas `initialize`.

**Fix em `run_benchmark.sh`** — warmup real com os 3 tools:
```bash
warmup() {
    local url=$1 name=$2
    info "Warming up $name (20 calls per tool)..."
    for i in $(seq 1 20); do
        curl -sf -X POST "$url" -H "Content-Type: application/json" \
            -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_products","arguments":{"category":"Electronics","min_price":50,"max_price":500,"limit":10}}}' \
            > /dev/null 2>&1 || true
    done
    ok "Warmup complete"
}
```

**Fix em `benchmark.js`:** aumentar `WARMUP_SECONDS` de `30` para `60`.

---

### I5 · Paralelizar `get_user_cart` no servidor Rust

**Arquivo:** `rust-server/src/main.rs`  
**Esforço:** Baixo  
**Impacto:** 🟡 Melhora consistência com os demais servidores

**Problema:**  
Rust faz `HGETALL` + `LRANGE` sequencialmente na mesma conexão Redis, depois HTTP. Deveria disparar HTTP e LRANGE em paralelo após o HGETALL — padrão seguido por Go, Quarkus, Micronaut, Python e Node.js.

**Fix:**
```rust
// Após obter cartHash via HGETALL:
let history_fut = tokio::spawn(async move {
    // LRANGE em conexão separada do pool
    let mut conn = pool.get().await.ok()?;
    redis::cmd("LRANGE").arg(&hist_key).arg(0i64).arg(4i64)
        .query_async::<Vec<String>>(&mut *conn).await.ok()
});
let product_res = self.http.get(format!("{}/products/{}", api_url, first_product_id)).send().await;
let history_raw = history_fut.await.ok().flatten().unwrap_or_default();
```

---

## 🟢 Nível 3 — Nice to Have

> Melhorias de qualidade analítica. Não afetam a fairness diretamente, mas tornam os resultados mais precisos e reprodutíveis.

---

### N1 · Cálculo correto de percentis consolidados

**Arquivo:** `benchmark/benchmark.js` (função `computeToolLatency`)  
**Esforço:** Médio

**Problema:**  
O `latency.p95` atual é a média aritmética dos p95 dos 3 tools (`avg(p95_search, p95_cart, p95_checkout)`). Isso não é o p95 real da distribuição conjunta — se `search` tem p95=130ms com 100k requests e `checkout` tem p95=90ms com 100k requests, a média (110ms) não representa o p95 real de 200k requests combinados.

**Fix** — média ponderada pelos counts:
```javascript
const totalCount = p95s.reduce((acc, _, i) => acc + counts[i], 0);
const weightedP95 = p95s.reduce((acc, val, i) => acc + val * counts[i], 0) / totalCount;
```

---

### N2 · Múltiplos runs com média automática

**Arquivo:** `benchmark/run_benchmark.sh`  
**Esforço:** Médio

Rodar cada servidor N vezes (ex: 3) e calcular média e desvio padrão. Os scripts `extract_stats.py` e `generate_table.py` já existem para análise multi-run — falta integrar ao fluxo principal. Reduz o impacto de variâncias pontuais (picos de CPU do host, GC pauses, etc.).

---

### N3 · Randomizar a ordem dos servidores por run

**Arquivo:** `benchmark/run_benchmark.sh`  
**Esforço:** Mínimo

Alternar a ordem de testes entre runs (ex: crescente no run A, decrescente no run B) e consolidar. Elimina completamente o viés de ordem sem precisar resetar o Redis entre servidores.

---

### N4 · Incluir limites da infraestrutura no `summary.json`

**Arquivo:** `benchmark/consolidate.py`  
**Esforço:** Mínimo

Adicionar ao output os limites de CPU/memória do Redis e API service. Dá contexto para quem lê os resultados de forma isolada, especialmente se Redis ou API service forem bottleneck em cenários de carga alta.

---

## Resumo

| ID | Item | Esforço | Impacto |
|---|---|---|---|
| **E1** | Fix HTTP pool Go (`MaxIdleConnsPerHost`) | Mínimo | 🔴 Alto |
| **E2** | Pipeline Redis no `checkout` (java, java-vt) | Baixo | 🟠 Médio |
| **E3** | I/O paralelo em java e java-vt (decisão de design) | Médio | 🔴 Alto |
| **E4** | Variar `user_id` por VU no k6 | Mínimo | 🟠 Médio |
| **I1** | Reset Redis entre servidores | Baixo | 🟡 Baixo-médio |
| **I2** | TCP_NODELAY uniforme | Baixo | 🟡 Baixo |
| **I3** | Documentar workers Node.js | Mínimo | 🟡 Visibilidade |
| **I4** | Warmup mais robusto (real tool calls) | Baixo | 🟡 Baixo |
| **I5** | `get_user_cart` paralelo no Rust | Baixo | 🟡 Baixo |
| **N1** | Percentis corretos (ponderados por count) | Médio | ⬜ Precisão |
| **N2** | Múltiplos runs + média/desvio padrão | Médio | ⬜ Reprodutibilidade |
| **N3** | Ordem aleatória de servidores | Mínimo | ⬜ Metodologia |
| **N4** | Infra (Redis/API) no `summary.json` | Mínimo | ⬜ Documentação |
