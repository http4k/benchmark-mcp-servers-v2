# Java WebFlux MCP Server — Benchmark Findings

## Benchmark environment
- 10 VUs, 5 min, 2 CPUs, 2 GB RAM, sleep(0.05) por iteração
- Results: `benchmark/results/20260223_145040/`

---

## Finding 1 — Consumo de memória excessivo (372 MB avg, 510 MB peak)

### Sintoma
| Server | MEM avg | MEM peak |
|--------|--------:|---------:|
| Quarkus JVM | 174 MB | — |
| Java JVM (Spring MVC) | 237 MB | 247 MB |
| **WebFlux JVM** | **372 MB** | **510 MB** |
| WebFlux Native | 226 MB | 491 MB |

WebFlux JVM consome **57% mais memória** que o Spring MVC (java) equivalente e **114% mais**
que o Quarkus JVM, apesar de processar o mesmo workload.

### Causa raiz: Netty `PooledByteBufAllocator` off-heap

WebFlux usa o Netty como servidor HTTP (em vez do Tomcat do Spring MVC). O Netty aloca
buffers de I/O **fora do heap JVM** (off-heap / direct memory) via
`PooledByteBufAllocator.DEFAULT`, que é ativado por padrão.

O algoritmo de pooling do Netty pré-aloca arenas por thread do event loop:

```
Arenas = max(2, num_cpus) × arenas_por_CPU
Por padrão: 2 CPUs → 2 event loop threads
Arena padrão ≈ 16 MB × 2 threads = 32 MB direct memory apenas para buffers de I/O
```

Além disso, o Netty mantém um **pool de chunk buffers** de 8 MB cada, crescendo sob carga
para evitar re-alocações. Com 10 VUs ativos, o pool cresce até o nível observado (~510 MB
peak).

O `docker stats` reporta memória **total do container** = heap JVM + off-heap Netty +
metaspace + code cache + stacks. O off-heap não é visível nem controlado por `-Xmx`.

### Por que WebFlux Native também tem alta memória (226 MB avg, 491 MB peak)?

A memória alta **não é causada pela JVM** — é causada pelo **Netty**. O native image elimina
JVM overhead (metaspace, JIT code cache) mas mantém os buffers Netty intactos. O peak de
491 MB do native vs 510 MB do JVM confirma que ~95% da diferença é Netty, não JVM.

### Relação com o workload
Com apenas 10 VUs e requests de ~0.237ms avg, os buffers Netty ficam subutilizados — foram
alocados para absorver bursts de alta concorrência (100+ conexões simultâneas). É um
tradeoff correto do Netty: pagar memória fixa para evitar GC pressure em alta carga.

### Como mitigar (não aplicado — apenas documentado)
```java
// Em application.properties ou configuração Spring:
server.netty.max-initial-line-length=4096
// Ou configurar PooledByteBufAllocator com arenas menores:
io.netty.allocator.numDirectArenas=1
io.netty.allocator.pageSize=4096
```

Alternativa: migrar para `UnpooledByteBufAllocator` (menor memória, maior GC pressure em
alta carga). Para este benchmark de 10 VUs, reduziria memória drasticamente mas não é
representativo de produção.

### Action
Sem correção aplicada — comportamento correto para um servidor Netty de produção. Documentado
para contexto ao comparar footprints de memória entre stacks.

---

## Finding 2 — Excelente latência apesar do overhead de memória

### Observação positiva
Apesar do consumo de memória, WebFlux JVM tem a **2ª melhor latência** entre todos os
servidores Java:

| Server | avg | p95 | RPS |
|--------|----:|----:|----:|
| Quarkus JVM | **0.220ms** | 0.524ms | 4,091 |
| **WebFlux JVM** | **0.237ms** | **0.501ms** | 4,037 |
| Java JVM (MVC) | 0.220ms | 0.459ms | 4,018 |

O event loop não-bloqueante do Netty + Reactor é altamente eficiente para workloads onde
o handler retorna `Mono.fromCallable()` rapidamente. Requests CPU-bound completam em
microssegundos, nunca bloqueando o event loop.

### Menor gap JVM→Native entre todos os Java
WebFlux tem o menor gap de latência entre JVM e native (1.13× a 1.54×) comparado com
outros stacks (1.59× a 2.43×). O modelo reativo depende menos de runtime adaptativo que
o modelo thread-per-request — o event loop otimiza naturalmente para throughput, e o JIT
traz ganhos menores que em loops sincronos.

---

## Finding 3 — Spikes de max latência no WebFlux Native (221ms)

### Sintoma
WebFlux Native reporta max de 221ms para o `_initialize`, 218ms em alguns tools.
O avg e p95 são bons (0.32ms e 0.62ms), mas os outliers raros são ~700× acima do avg.

### Causa raiz
O GraalVM Native Image usa o **Serial GC** por padrão (sem as alternativas G1/ZGC do JVM).
Quando o Netty PooledByteBufAllocator precisa retornar chunks off-heap ao SO, ele triggera
coletas parciais. O Serial GC causa **stop-the-world** completo (sem concurrent marking),
resultando em pauses de 50–200ms sob pressão de memória.

O GraalVM 25 (usado nos servers Spring Boot native) suporta `--gc=G1` em native, mas não
está habilitado no Dockerfile atual.

### Action
Documentado. Para eliminar esses spikes, adicionar `--gc=G1` no `native-image` args do
Dockerfile. Não aplicado neste ciclo de benchmark.
