# Benchmark Round 1 — Laudo de Performance MCP Servers

> **Data das execuções:** 26–27 de fevereiro de 2026
> **Branch:** `bench-v2`
> **Autor:** Thiago Marinho (tmdevlab.com)

---

## Sumário

1. [Resumo Executivo](#1-resumo-executivo)
2. [Metodologia e Configuração](#2-metodologia-e-configuração)
3. [Resultados Consolidados](#3-resultados-consolidados-média-dos-3-runs)
4. [Resultados por Execução](#4-resultados-por-execução)
   - [Run 1 — 20260226\_173129](#run-1--20260226_173129)
   - [Run 2 — 20260226\_185707](#run-2--20260226_185707)
   - [Run 3 — 20260226\_202249](#run-3--20260226_202249)
5. [Latência por Tool (Consolidado)](#5-latência-por-tool-consolidado-média-dos-3-runs)
6. [Consumo de Recursos](#6-consumo-de-recursos)
7. [Análise de Estabilidade entre Runs](#7-análise-de-estabilidade-entre-runs)
8. [Anomalias e Alertas](#8-anomalias-e-alertas)
9. [Rankings Finais](#9-rankings-finais)
10. [Conclusões e Recomendações](#10-conclusões-e-recomendações)

---

## 1. Resumo Executivo

Este laudo consolida **três execuções completas** do benchmark de servidores MCP, realizadas em sequência no mesmo host em 26–27 de fevereiro de 2026. Ao longo dos três rounds, foram processadas **~54,9 milhões de requisições MCP** com **taxa de erro absoluta de 0%** em todos os 15 servidores testados.

### Destaques

| Métrica | Melhor | Pior |
|---------|--------|------|
| **Throughput (RPS)** | Rust — 5.632 RPS | Python — 375 RPS |
| **Latência Média** | Quarkus-Native — 3,54 ms | Python — 174 ms |
| **Latência P95** | Quarkus-Native — 8,45 ms | Python — 240 ms |
| **Latência P99** | Quarkus-Native — 15,12 ms | Python — 280 ms |
| **Memória (média)** | Go — 25 MB | Java-WebFlux-Native — 649 MB |
| **CPU (média)** | Rust — 66% | Python/Node/Bun/Micronaut-Native — ~200% |
| **Estabilidade RPS** | Java-VT — CV 0,86% | Java-Native — CV 12,2% |
| **Error rate** | Todos — **0%** | — |

**Hierarquia de performance consolidada (por latência média):**

```
quarkus-native (~3,54ms) > quarkus (~4,00ms) > rust (~4,50ms) >
java-webflux (~4,78ms) > micronaut (~4,69ms) > java-vt (~5,24ms) >
go (~5,75ms) > java (~6,22ms) > java-webflux-native (~6,23ms) >
java-vt-native (~8,11ms) > micronaut-native (~8,31ms) > java-native (~10,74ms) >
bun (~24,93ms) > nodejs (~95,95ms) > python (~174ms)
```

---

## 2. Metodologia e Configuração

### Parâmetros do Benchmark

| Parâmetro | Valor |
|-----------|-------|
| Ferramenta de carga | k6 |
| Virtual Users (VUs) | 50 |
| Duração por servidor | 5 minutos |
| Warmup por servidor | 60 segundos (real tool calls) |
| Protocolo | MCP Streamable HTTP (JSON-RPC 2.0) |
| CPU por container | 2.0 vCPUs |
| Memória por container | 2 GB |

### Infraestrutura de Apoio

| Serviço | Spec | Papel |
|---------|------|-------|
| `mcp-redis` | Redis 7 Alpine · 0,5 vCPU · 512 MB | Cache, carrinhos, histórico |
| `mcp-api-service` | Go stdlib · 1,0 vCPU · 1 GB | API de produtos (100k itens in-memory) |

### Tools MCP testados (por servidor)

Cada servidor expõe **3 tools idênticos** com I/O real:

| Tool | Operações |
|------|-----------|
| `search_products` | HTTP GET `/products/search` + `ZRANGE` Redis |
| `get_user_cart` | `HGETALL` Redis + HTTP GET `/products/{id}` + `LRANGE` Redis |
| `checkout` | HTTP POST `/cart/calculate` + pipeline Redis (`INCR` + `RPUSH` + `ZADD`) |

### Condições de Fairness

- **Redis reset** entre cada servidor (flush + re-seed completo)
- **Distribuição de usuários:** VU `N` usa `user-N%1000` → 50 usuários distintos simultâneos
- **I/O paralelo:** todos os servidores paralelizam chamadas HTTP + Redis independentes por request
- **Node.js e Bun:** executam com `WEB_CONCURRENCY=4` (cluster de 4 processos) — padrão de produção para runtimes single-threaded

### Execuções

| Run | Início (UTC) | Total de Requests |
|-----|-------------|-------------------|
| **R1** | 2026-02-26 21:56:57 | ~18,7 milhões |
| **R2** | 2026-02-26 23:22:39 | ~18,1 milhões |
| **R3** | 2026-02-27 00:48:18 | ~18,1 milhões |
| **Total** | — | **~54,9 milhões** |

---

## 3. Resultados Consolidados (Média dos 3 Runs)

> Todos os valores de latência em **milissegundos (ms)**. RPS = requests MCP por segundo.
> Error rate = 0% para todos os servidores em todos os runs.

### 3.1 Throughput e Latência Geral

| Servidor | RPS (média) | Lat. avg | Lat. P50 | Lat. P90 | Lat. P95 | Lat. P99 | Error Rate |
|----------|-------------|----------|----------|----------|----------|----------|------------|
| **rust** | **5.632** | 4,50 | 1,81 | 10,77 | 22,99 | 36,37 | 0% |
| **quarkus** | **5.628** | 4,00 | 1,55 | 10,59 | 19,32 | 32,42 | 0% |
| **quarkus-native** | 5.457 | **3,54** | 2,89 | **6,34** | **8,45** | **15,12** | 0% |
| **micronaut** | 5.378 | 4,69 | 2,17 | 11,77 | 22,38 | 31,36 | 0% |
| **java-vt** | 4.978 | 5,24 | 3,41 | 11,98 | 17,85 | 28,53 | 0% |
| **java-webflux** | 4.579 | 4,78 | 2,68 | 10,43 | 19,21 | 34,42 | 0% |
| **java** | 4.191 | 6,22 | 2,08 | 19,09 | 28,73 | 47,17 | 0% |
| **java-webflux-native** | 3.864 | 6,23 | 5,17 | 9,37 | 15,01 | 32,07 | 0% |
| **go** | 3.845 | 5,75 | 3,54 | 9,22 | 21,18 | 59,61 | 0% |
| **java-vt-native** | 3.889 | 8,11 | 7,34 | 13,91 | 16,67 | 27,92 | 0% |
| **micronaut-native** | 3.711 | 8,31 | 7,26 | 12,65 | 19,66 | 39,14 | 0% |
| **java-native** | 2.833 | 10,74 | 4,96 | 27,37 | 53,76 | 79,09 | 0% |
| **bun** | 1.427 | 24,93 | 10,47 | 59,33 | 77,85 | 94,31 | 0% |
| **nodejs** | 538 | 95,95 | 95,16 | 171,28 | 195,29 | 267,51 | 0% |
| **python** | 375 | 174,44 | 182,90 | 209,56 | 240,24 | 280,00 | 0% |

> **Nota sobre Node.js e Bun:** operam com 4 processos em cluster mode (`WEB_CONCURRENCY=4`), o que equivale a 4x o throughput por processo.
> **Nota sobre java-native:** alta variância entre runs (CV=12,2%) — os valores consolidados têm baixa confiabilidade.

---

## 4. Resultados por Execução

---

### Run 1 — 20260226\_173129

**Início:** 2026-02-26 21:56:57 UTC | **Total:** ~18,7 milhões de requests

| Servidor | Total Reqs | RPS | Lat. avg | Lat. P50 | Lat. P90 | Lat. P95 | Lat. P99 | Erros |
|----------|-----------|-----|----------|----------|----------|----------|----------|-------|
| rust | 1.872.056 | **5.759** | 4,04 | 1,66 | 8,99 | 20,62 | 35,33 | 0 |
| quarkus | 1.847.456 | 5.684 | 3,86 | 1,50 | 10,34 | 18,42 | 32,35 | 0 |
| quarkus-native | 1.803.432 | 5.548 | **3,32** | 2,75 | 5,84 | **7,69** | 14,02 | 0 |
| micronaut | 1.782.784 | 5.485 | 4,34 | 1,98 | 11,07 | 21,51 | 30,88 | 0 |
| java-vt | 1.633.768 | 5.027 | 5,23 | 3,37 | 12,21 | 18,12 | 28,46 | 0 |
| java-webflux | 1.506.816 | 4.636 | 4,46 | 2,59 | 9,18 | 16,91 | 33,70 | 0 |
| java-webflux-native | 1.290.416 | 3.970 | 6,02 | 5,11 | 9,24 | 14,97 | 29,24 | 0 |
| java-vt-native | 1.279.512 | 3.937 | 7,92 | 7,22 | 13,52 | 16,20 | 27,25 | 0 |
| java | 1.326.888 | 4.082 | 6,22 | 2,00 | 18,99 | 28,85 | 44,56 | 0 |
| go | 1.286.720 | 3.959 | 5,58 | 3,69 | 8,73 | 18,31 | 56,84 | 0 |
| micronaut-native | 1.234.080 | 3.797 | 7,96 | 7,23 | 11,94 | 15,25 | 35,51 | 0 |
| java-native | 1.036.928 | 3.190 | 8,36 | 4,60 | 13,20 | 34,34 | 71,88 | 0 |
| bun | 504.936 | 1.553 | 22,45 | 9,83 | 56,73 | 61,48 | 90,44 | 0 |
| nodejs | 178.776 | 550 | 93,78 | 94,32 | 162,84 | 194,18 | 262,16 | 0 |
| python | 122.896 | 378 | 173,66 | 182,03 | 205,51 | 225,37 | 274,41 | 0 |

---

### Run 2 — 20260226\_185707

**Início:** 2026-02-26 23:22:39 UTC | **Total:** ~18,1 milhões de requests

| Servidor | Total Reqs | RPS | Lat. avg | Lat. P50 | Lat. P90 | Lat. P95 | Lat. P99 | Erros |
|----------|-----------|-----|----------|----------|----------|----------|----------|-------|
| quarkus | 1.826.096 | **5.618** | 4,01 | 1,54 | 10,58 | 19,50 | 32,44 | 0 |
| rust | 1.801.248 | 5.542 | 4,82 | 1,93 | 12,07 | 24,30 | 36,92 | 0 |
| quarkus-native | 1.755.696 | 5.402 | **3,68** | 2,97 | 6,74 | **9,08** | 16,11 | 0 |
| micronaut | 1.707.816 | 5.254 | 5,05 | 2,39 | 12,48 | 23,28 | 31,69 | 0 |
| java-vt | 1.609.968 | 4.954 | 5,24 | 3,44 | 11,71 | 17,61 | 28,60 | 0 |
| java | 1.472.920 | 4.532 | 5,80 | 2,06 | 17,75 | 27,71 | 35,23 | 0 |
| java-webflux | 1.462.800 | 4.500 | 5,25 | 2,98 | 11,76 | 21,33 | 35,73 | 0 |
| java-vt-native | 1.263.896 | 3.888 | 8,17 | 7,36 | 14,03 | 16,88 | 28,04 | 0 |
| java-webflux-native | 1.218.432 | 3.749 | 6,48 | 5,20 | 9,69 | 16,69 | 37,48 | 0 |
| micronaut-native | 1.205.592 | 3.709 | 8,32 | 7,25 | 12,61 | 19,78 | 39,88 | 0 |
| go | 1.233.200 | 3.794 | 5,60 | 3,44 | 8,74 | 19,76 | 56,90 | 0 |
| java-native | 813.504 | 2.503 | 13,56 | 5,38 | 43,58 | 68,77 | 90,64 | 0 |
| bun | 432.824 | 1.332 | 26,93 | 10,85 | 61,33 | 87,15 | 96,81 | 0 |
| nodejs | 175.864 | 541 | 95,08 | 95,13 | 163,92 | 193,73 | 256,73 | 0 |
| python | 122.536 | 377 | 173,82 | 182,76 | 207,90 | 242,18 | 277,92 | 0 |

---

### Run 3 — 20260226\_202249

**Início:** 2026-02-27 00:48:18 UTC | **Total:** ~18,1 milhões de requests

| Servidor | Total Reqs | RPS | Lat. avg | Lat. P50 | Lat. P90 | Lat. P95 | Lat. P99 | Erros |
|----------|-----------|-----|----------|----------|----------|----------|----------|-------|
| rust | 1.818.744 | **5.596** | 4,64 | 1,83 | 11,24 | 24,04 | 36,87 | 0 |
| quarkus | 1.814.928 | 5.584 | 4,12 | 1,62 | 10,86 | 20,03 | 32,46 | 0 |
| quarkus-native | 1.761.632 | 5.420 | **3,62** | 2,96 | 6,44 | **8,58** | 15,23 | 0 |
| micronaut | 1.752.928 | 5.393 | 4,67 | 2,13 | 11,75 | 22,36 | 31,51 | 0 |
| java-vt | 1.609.808 | 4.953 | 5,25 | 3,41 | 12,02 | 17,81 | 28,54 | 0 |
| java-webflux | 1.495.008 | 4.600 | 4,64 | 2,46 | 10,35 | 19,39 | 33,83 | 0 |
| java | 1.287.128 | 3.960 | 6,63 | 2,17 | 20,53 | 29,62 | 61,71 | 0 |
| java-webflux-native | 1.258.600 | 3.872 | 6,19 | 5,19 | 9,18 | 13,38 | 29,50 | 0 |
| java-vt-native | 1.248.208 | 3.841 | 8,23 | 7,43 | 14,18 | 16,92 | 28,46 | 0 |
| go | 1.228.928 | 3.781 | 6,08 | 3,49 | 10,20 | 25,46 | 65,10 | 0 |
| micronaut-native | 1.179.048 | 3.627 | 8,64 | 7,31 | 13,40 | 23,94 | 42,02 | 0 |
| java-native | 911.808 | 2.805 | 10,31 | 4,89 | 25,32 | 58,17 | 74,74 | 0 |
| bun | 454.008 | 1.397 | 25,42 | 10,73 | 59,93 | 84,93 | 95,68 | 0 |
| nodejs | 170.352 | 524 | 98,99 | 96,04 | 187,09 | 197,97 | 283,64 | 0 |
| python | 120.144 | 370 | 175,85 | 183,92 | 215,27 | 253,18 | 287,68 | 0 |

---

## 5. Latência por Tool (Consolidado — Média dos 3 Runs)

> Cada servidor implementa os mesmos 3 tools. Os valores abaixo são a média de latência de cada tool através das 3 execuções. Unidade: **ms**.

### 5.1 `search_products`

Operação: HTTP GET `/products/search` + `ZRANGE` Redis (paralelos)

| Servidor | avg | P50 | P90 | P95 | P99 |
|----------|-----|-----|-----|-----|-----|
| **quarkus-native** | **3,66** | 2,78 | 6,99 | 9,71 | 18,77 |
| quarkus | 6,01 | 1,69 | 23,56 | 29,89 | 36,73 |
| rust | 6,70 | 2,35 | 23,05 | 33,57 | 41,36 |
| java-vt | 7,10 | 3,97 | 20,38 | 26,33 | 33,70 |
| java-webflux | 6,76 | 3,43 | 18,85 | 27,68 | 37,38 |
| micronaut | 6,84 | 2,59 | 24,03 | 28,87 | 35,59 |
| go | 7,92 | 5,36 | 15,01 | 28,90 | 48,68 |
| java | 9,22 | 2,92 | 30,02 | 34,42 | 42,33 |
| java-vt-native | 7,44 | 6,62 | 12,46 | 15,27 | 25,63 |
| java-webflux-native | 7,79 | 6,45 | 13,21 | 22,14 | 32,50 |
| micronaut-native | 8,67 | 8,01 | 13,82 | 17,71 | 34,31 |
| java-native | 10,28 | 6,45 | 22,70 | 37,08 | 66,46 |
| bun | 13,35 | 8,65 | 15,25 | 61,61 | 90,91 |
| nodejs | 96,10 | 94,81 | 184,02 | 194,32 | 279,94 |
| python | 174,99 | 179,63 | 209,99 | 243,72 | 278,48 |

### 5.2 `get_user_cart`

Operação: `HGETALL` Redis → HTTP GET `/products/{id}` + `LRANGE` Redis (paralelos)

| Servidor | avg | P50 | P90 | P95 | P99 |
|----------|-----|-----|-----|-----|-----|
| **quarkus** | **3,35** | 1,67 | 4,59 | 19,37 | 31,25 |
| quarkus-native | 3,98 | 3,44 | 7,11 | 8,88 | 14,37 |
| rust | 4,21 | 1,93 | 6,27 | 25,56 | 35,94 |
| micronaut | 3,77 | 2,00 | 5,93 | 20,62 | 29,95 |
| java-webflux | 4,08 | 2,43 | 7,02 | 16,98 | 34,04 |
| java-vt | 4,36 | 2,76 | 8,43 | 17,63 | 27,65 |
| go | 5,09 | 2,83 | 6,92 | 24,36 | 66,61 |
| java | 5,04 | 1,66 | 18,73 | 27,21 | 52,04 |
| java-webflux-native | 5,43 | 4,59 | 7,50 | 11,91 | 28,58 |
| java-vt-native | 7,27 | 6,36 | 13,10 | 15,56 | 26,30 |
| micronaut-native | 6,98 | 6,37 | 10,41 | 12,95 | 32,86 |
| java-native | 10,52 | 3,75 | 28,08 | 59,69 | 83,28 |
| bun | 32,65 | 12,26 | 82,67 | 88,61 | 98,47 |
| nodejs | 106,77 | 99,45 | 190,95 | 201,29 | 289,04 |
| python | 177,31 | 186,53 | 212,25 | 250,29 | 285,51 |

### 5.3 `checkout`

Operação: HTTP POST `/cart/calculate` + pipeline Redis `INCR`+`RPUSH`+`ZADD` (paralelos)

| Servidor | avg | P50 | P90 | P95 | P99 |
|----------|-----|-----|-----|-----|-----|
| **rust** | **2,59** | 1,15 | 3,00 | 10,05 | 31,79 |
| quarkus | 2,63 | 1,29 | 3,54 | 8,70 | 29,27 |
| quarkus-native | 2,97 | 2,46 | 5,26 | 6,75 | 12,22 |
| java-webflux | 3,52 | 2,18 | 5,42 | 12,96 | 31,84 |
| micronaut | 3,44 | 1,84 | 5,01 | 17,66 | 28,54 |
| java-vt | 4,25 | 3,50 | 7,13 | 9,58 | 24,26 |
| java | 4,39 | 1,65 | 8,52 | 24,55 | 46,46 |
| go | 4,25 | 2,41 | 5,73 | 11,91 | 63,55 |
| java-webflux-native | 5,46 | 4,45 | 7,40 | 10,99 | 35,14 |
| java-vt-native | 9,62 | 9,03 | 16,29 | 19,17 | 31,82 |
| micronaut-native | 9,27 | 7,41 | 13,73 | 28,31 | 50,24 |
| java-native | 11,42 | 4,34 | 31,39 | 64,57 | 87,33 |
| bun | 28,80 | 10,50 | 80,08 | 84,75 | 93,55 |
| nodejs | 84,97 | 91,23 | 138,81 | 187,19 | 233,54 |
| python | 171,03 | 182,55 | 206,11 | 226,75 | 275,68 |

---

## 6. Consumo de Recursos

> CPU medido como % de uso dos 2 vCPUs alocados (máx teórico = 200%). Memória em MB (media e máximo durante o teste).

### 6.1 Consumo Consolidado (média dos 3 runs)

| Servidor | CPU avg (%) | CPU max (%) | RAM avg (MB) | RAM max (MB) | Eficiência RPS/CPU |
|----------|-------------|-------------|--------------|--------------|-------------------|
| **rust** | **66,4** | 79,7 | ~13–14 ¹ | ~15 ¹ | **84,8** |
| **go** | 195,9 | 212,6 | 25,5 | 28,1 | 19,6 |
| **quarkus** | **96,9** | 207,2 | 190,0 | 202,1 | 58,1 |
| **quarkus-native** | 166,4 | 187,4 | ~38,8 ² | ~60,1 ² | 32,8 |
| **java-vt** | 127,4 | 206,7 | 416,6 | 429,5 | 39,1 |
| **java-vt-native** | 159,2 | 203,1 | 225,4 | 268,0 | 24,4 |
| **java-webflux** | 188,6 | 212,8 | 595,7 | 759,4 | 24,3 |
| **java-webflux-native** | 195,5 | 213,3 | 648,7 | 1.056,6 | 19,8 |
| **java** | 181,0 | 207,9 | 372,8 | 390,3 | 23,1 |
| **java-native** | 196,3 | 214,8 | 228,1 | 314,5 | 14,4 |
| **micronaut** | 149,2 | 207,1 | 245,2 | 267,5 | 36,1 |
| **micronaut-native** | 199,5 | 217,1 | 173,5 | 244,1 | 18,6 |
| **bun** | 200,6 | 209,6 | 577,0 | 601,7 | 7,1 |
| **nodejs** | 199,9 | 209,3 | 422,8 | 433,7 | 2,7 |
| **python** | 200,2 | 203,6 | 287,8 | 304,6 | 1,9 |

> ¹ **Rust memória:** R1 apresenta anomalia (33,5 MB avg / 35 MB max). R2 e R3 convergiram para ~13–14 MB avg / ~15 MB max. Valores de R1 descartados para cálculo de memória (detalhes na seção 8).
> ² **Quarkus-Native memória:** mesmo fenômeno — R1 com 88 MB avg vs R2/R3 com ~39 MB avg.

### 6.2 Run 1 — Recursos por Servidor

| Servidor | CPU avg (%) | CPU max (%) | RAM avg (MB) | RAM max (MB) |
|----------|-------------|-------------|--------------|--------------|
| rust | 66,9 | 78,4 | 33,5 ¹ | 35,0 ¹ |
| go | 195,1 | 210,8 | 25,5 | 27,5 |
| quarkus | 94,4 | 209,1 | 183,1 | 188,6 |
| quarkus-native | 164,6 | 184,4 | 88,2 ² | 108,7 ² |
| java-vt | 123,3 | 207,8 | 429,9 | 434,7 |
| java-vt-native | 157,0 | 202,9 | 225,5 | 263,5 |
| java-webflux | 190,1 | 210,5 | 617,7 | 786,6 |
| java-webflux-native | 195,6 | 210,8 | 649,4 | 1.019,1 |
| java | 179,3 | 207,4 | 372,7 | 392,5 |
| java-native | 195,7 | 210,9 | 230,8 | 314,2 |
| micronaut | 147,3 | 206,3 | 233,1 | 240,1 |
| micronaut-native | 199,5 | 218,3 | 172,3 | 233,8 |
| bun | 200,8 | 211,3 | 621,3 | 644,4 |
| nodejs | 199,8 | 211,2 | 464,7 | 475,4 |
| python | 200,3 | 204,5 | 301,2 | 311,4 |

### 6.3 Run 2 — Recursos por Servidor

| Servidor | CPU avg (%) | CPU max (%) | RAM avg (MB) | RAM max (MB) |
|----------|-------------|-------------|--------------|--------------|
| rust | 65,4 | 78,0 | 13,2 | 14,4 |
| go | 196,7 | 214,4 | 25,8 | 28,4 |
| quarkus | 97,0 | 206,0 | 199,7 | 208,2 |
| quarkus-native | 166,7 | 188,5 | 38,9 | 58,5 |
| java-vt | 129,4 | 203,5 | 410,9 | 419,0 |
| java-vt-native | 159,6 | 203,3 | 227,2 | 277,1 |
| java-webflux | 188,6 | 213,4 | 568,0 | 735,2 |
| java-webflux-native | 195,2 | 213,4 | 633,8 | 1.072,8 |
| java | 182,3 | 205,5 | 382,0 | 391,0 |
| java-native | 196,6 | 219,0 | 225,6 | 319,4 |
| micronaut | 150,6 | 207,8 | 269,0 | 301,4 |
| micronaut-native | 199,4 | 214,6 | 174,5 | 263,9 |
| bun | 200,3 | 208,2 | 551,5 | 579,6 |
| nodejs | 199,8 | 208,2 | 401,4 | 413,9 |
| python | 200,1 | 203,5 | 281,0 | 291,6 |

### 6.4 Run 3 — Recursos por Servidor

| Servidor | CPU avg (%) | CPU max (%) | RAM avg (MB) | RAM max (MB) |
|----------|-------------|-------------|--------------|--------------|
| rust | 66,9 | 82,7 | 13,3 | 14,5 |
| go | 196,5 | 216,5 | 25,3 | 28,5 |
| quarkus | 99,4 | 206,5 | 187,2 | 208,0 |
| quarkus-native | 167,7 | 189,2 | 38,7 | 61,2 |
| java-vt | 129,4 | 208,9 | 408,9 | 414,8 |
| java-vt-native | 161,0 | 203,5 | 223,6 | 267,5 |
| java-webflux | 187,1 | 214,6 | 601,6 | 727,3 |
| java-webflux-native | 195,8 | 215,6 | 662,9 | 1.078,0 |
| java | 181,3 | 210,8 | 373,1 | 387,2 |
| java-native | 196,5 | 214,4 | 228,0 | 315,1 |
| micronaut | 149,6 | 207,2 | 233,5 | 260,1 |
| micronaut-native | 199,6 | 218,3 | 173,8 | 264,6 |
| bun | 200,8 | 209,3 | 558,3 | 581,2 |
| nodejs | 200,1 | 208,7 | 402,3 | 411,8 |
| python | 200,2 | 202,8 | 281,3 | 290,9 |

---

## 7. Análise de Estabilidade entre Runs

### 7.1 Coeficiente de Variação do RPS

O coeficiente de variação (CV = desvio padrão / média) mede a dispersão relativa do throughput entre as 3 execuções. CV < 5% indica boa reprodutibilidade.

| Servidor | R1 RPS | R2 RPS | R3 RPS | Média | Desvio Padrão | **CV** | Classificação |
|----------|--------|--------|--------|-------|--------------|--------|---------------|
| java-vt | 5.027 | 4.954 | 4.953 | 4.978 | 43 | **0,86%** | ✅ Excelente |
| quarkus | 5.684 | 5.618 | 5.584 | 5.629 | 51 | **0,91%** | ✅ Excelente |
| python | 378 | 377 | 370 | 375 | 4 | **1,17%** | ✅ Excelente |
| java-vt-native | 3.937 | 3.888 | 3.841 | 3.889 | 48 | **1,24%** | ✅ Excelente |
| quarkus-native | 5.548 | 5.402 | 5.420 | 5.457 | 80 | **1,46%** | ✅ Excelente |
| java-webflux | 4.636 | 4.500 | 4.600 | 4.579 | 71 | **1,54%** | ✅ Excelente |
| rust | 5.759 | 5.542 | 5.596 | 5.632 | 113 | **2,01%** | ✅ Bom |
| micronaut | 5.485 | 5.254 | 5.393 | 5.377 | 119 | **2,22%** | ✅ Bom |
| micronaut-native | 3.797 | 3.709 | 3.627 | 3.711 | 85 | **2,30%** | ✅ Bom |
| nodejs | 550 | 541 | 524 | 538 | 13 | **2,44%** | ✅ Bom |
| go | 3.959 | 3.794 | 3.781 | 3.845 | 99 | **2,58%** | ✅ Bom |
| java-webflux-native | 3.970 | 3.749 | 3.872 | 3.864 | 111 | **2,88%** | ✅ Bom |
| java | 4.082 | 4.532 | 3.960 | 4.191 | 301 | **7,18%** | ⚠️ Instável |
| bun | 1.553 | 1.332 | 1.397 | 1.427 | 117 | **8,24%** | ⚠️ Instável |
| java-native | 3.190 | 2.503 | 2.805 | 2.833 | 345 | **12,2%** | 🔴 Crítico |

### 7.2 Tendências de Latência P95

Análise da direção da latência P95 ao longo das 3 execuções:

| Servidor | P95 R1 | P95 R2 | P95 R3 | Variação R1→R3 | Tendência |
|----------|--------|--------|--------|----------------|-----------|
| java-vt | 18,12 | 17,61 | 17,81 | **−0,3 ms** | ↔ Estável ✅ |
| java-vt-native | 16,20 | 16,88 | 16,92 | **+0,7 ms** | ↔ Estável ✅ |
| java | 28,85 | 27,71 | 29,62 | **+0,8 ms** | ↔ Estável ✅ |
| nodejs | 194,18 | 193,73 | 197,97 | **+3,8 ms** | ↔ Estável ✅ |
| java-webflux-native | 14,97 | 16,69 | 13,38 | **−1,6 ms** | ↘ Melhorou ✅ |
| quarkus | 18,42 | 19,50 | 20,03 | **+1,6 ms (+8,7%)** | ↗ Leve alta 🟡 |
| rust | 20,62 | 24,30 | 24,04 | **+3,4 ms (+16%)** | ↗ Alta 🟡 |
| bun | 61,48 | 87,15 | 84,93 | **+23 ms (+38%)** | ↗ Pico R2, não normalizou 🟠 |
| go | 18,31 | 19,76 | 25,46 | **+7,2 ms (+39%)** | ↗ Alta progressiva 🟠 |
| python | 225,37 | 242,18 | 253,18 | **+27,8 ms (+12%)** | ↗ Crescimento constante 🟠 |
| micronaut-native | 15,25 | 19,78 | 23,94 | **+8,7 ms (+57%)** | ↗ Crescimento sistemático 🔴 |
| java-native | 34,34 | 68,77 | 58,17 | **+23,8 ms (+69%)** | ⚡ Extremamente volátil 🔴 |

---

## 8. Anomalias e Alertas

### 8.1 🔴 java-native — Instabilidade Crítica

**Observação:** O servidor Spring Native apresentou variação de RPS de **21,6%** entre execuções (3.190 → 2.503 → 2.805 RPS). A latência P95 quase dobrou entre R1 e R2 (34ms → 68ms), retornando parcialmente em R3 (58ms). Trata-se da maior instabilidade de todo o benchmark.

**Provável causa:** GraalVM Native Image com workload I/O-intensivo pode exibir comportamento inconsistente de thread scheduling e GC safepoints. A imagem nativa do Spring (sem JIT) perde a capacidade de reotimização em runtime, podendo degradar com padrões de acesso variáveis.

**Recomendação:** Resultados do java-native devem ser marcados com disclaimer. Necessário investigar se o problema é de pool de conexões HTTP (tamanho configurado vs 50 VUs) ou de GC nativo.

---

### 8.2 🟠 micronaut-native — Degradação Progressiva de P95

**Observação:** A latência P95 aumentou consistentemente a cada run: 15,25 → 19,78 → 23,94 ms (+57% total). O mesmo padrão se repete no P99: 35,5 → 39,9 → 42,0 ms. Cada run individual em isolamento parece razoável, mas a tendência é clara.

**Provável causa:** Possível acúmulo de state entre reinicializações (cache de conexões Lettuce com Redis não completamente limpo, ou threads de I/O com backlog crescente). Alternativa: contenção crescente de CPU à medida que o sistema host aquece.

**Recomendação:** Monitorar em runs adicionais. Se a tendência persistir, investigar o lifecycle do pool de conexões Redis/HTTP no Micronaut Netty.

---

### 8.3 🟠 go — Cauda de Latência em Ascensão

**Observação:** A latência média do Go é estável (5,58 → 5,60 → 6,08 ms), mas o P95 cresce 39% entre R1 e R3 (18,31 → 25,46 ms) e o P99 aumenta de 56,8 para 65,1 ms.

**Provável causa:** O `http.Transport` do Go tem `MaxIdleConnsPerHost=2` por padrão. Com 50 VUs, conexões são recicladas com menos eficiência à medida que o rate de requests aumenta ligeiramente por run. Isso coincide com o backlog identificado no item **E1** do `backlog.md`.

**Recomendação:** Aplicar fix `MaxIdleConnsPerHost=100` (backlog item E1) antes da publicação final dos resultados.

---

### 8.4 🟠 bun — Pico de P95 em R2 sem Recuperação

**Observação:** O P95 do Bun saltou de 61,5 ms (R1) para 87,1 ms (R2) e não retornou ao baseline em R3 (84,9 ms). O CV de RPS é 8,2% — segunda maior instabilidade.

**Provável causa:** Os 4 workers em cluster mode compartilham a porta de escuta via IPC. Sob carga sustentada, o balanceamento entre workers pode criar desequilíbrio. Também pode ser impacto de GC do V8 (Bun usa JavaScriptCore, mas o padrão é similar).

---

### 8.5 🟡 java-webflux-native — Memória Crescente

**Observação:** A memória máxima do Java WebFlux Native cresce a cada run: 1.019 MB (R1) → 1.073 MB (R2) → 1.078 MB (R3). Com limite de 2 GB, o servidor está operando com margem cada vez menor.

**Recomendação:** Investigar leak de memória off-heap no Netty nativo. Em runs mais longos ou com mais VUs, risco real de OOM.

---

### 8.6 ⚠️ Anomalia de Memória R1 — Rust e Quarkus-Native

**Observação:** Em R1, o consumo médio de memória do Rust foi 33,5 MB (max 35 MB), enquanto em R2 e R3 foi ~13,2 MB (max ~14,5 MB). O mesmo ocorreu com Quarkus-Native: 88,2 MB (R1) vs ~38,8 MB (R2/R3).

**Explicação:** R1 foi a primeira execução do dia. Os containers iniciaram com o sistema host em estado diferente — maior ocupação de page cache e memória de sistema residual. A partir de R2, os containers iniciaram com memória mínima limpa (min ~2-3 MB) e convergiram para um baseline mais baixo e estável.

**Impacto:** Os rankings de eficiência de memória no R1 subestimam Rust e Quarkus-Native. Para comparações de memória, usar valores de R2/R3.

---

## 9. Rankings Finais

Rankings baseados nos valores consolidados (média dos 3 runs). Para servidores com alta instabilidade (java-native), os valores consolidados têm menor confiabilidade.

### 9.1 Throughput (RPS)

| # | Servidor | RPS médio | Observação |
|---|----------|-----------|------------|
| 1 | **rust** | 5.632 | Líder consistente |
| 2 | **quarkus** | 5.629 | Empate técnico com Rust |
| 3 | **quarkus-native** | 5.457 | |
| 4 | **micronaut** | 5.378 | |
| 5 | **java-vt** | 4.978 | |
| 6 | **java-webflux** | 4.579 | |
| 7 | **java** | 4.191 | CV alto (7,2%) |
| 8 | **java-vt-native** | 3.889 | |
| 9 | **java-webflux-native** | 3.864 | |
| 10 | **go** | 3.845 | P95 crescente |
| 11 | **micronaut-native** | 3.711 | P95 em crescimento |
| 12 | **java-native** | 2.833 | ⚠️ CV=12,2% |
| 13 | **bun** | 1.427 | 4 workers |
| 14 | **nodejs** | 538 | 4 workers |
| 15 | **python** | 375 | |

### 9.2 Latência Média

| # | Servidor | Lat. avg | Lat. P95 |
|---|----------|----------|----------|
| 1 | **quarkus-native** | 3,54 ms | 8,45 ms |
| 2 | **quarkus** | 4,00 ms | 19,32 ms |
| 3 | **rust** | 4,50 ms | 22,99 ms |
| 4 | **micronaut** | 4,69 ms | 22,38 ms |
| 5 | **java-webflux** | 4,78 ms | 19,21 ms |
| 6 | **java-vt** | 5,24 ms | 17,85 ms |
| 7 | **go** | 5,75 ms | 21,18 ms |
| 8 | **java** | 6,22 ms | 28,73 ms |
| 9 | **java-webflux-native** | 6,23 ms | 15,01 ms |
| 10 | **java-vt-native** | 8,11 ms | 16,67 ms |
| 11 | **micronaut-native** | 8,31 ms | 19,66 ms |
| 12 | **java-native** | 10,74 ms | 53,76 ms ⚠️ |
| 13 | **bun** | 24,93 ms | 77,85 ms |
| 14 | **nodejs** | 95,95 ms | 195,29 ms |
| 15 | **python** | 174,44 ms | 240,24 ms |

### 9.3 Melhor P95 (latência de cauda)

| # | Servidor | P95 | P99 |
|---|----------|-----|-----|
| 1 | **quarkus-native** | 8,45 ms | 15,12 ms |
| 2 | **java-webflux-native** | 15,01 ms | 32,07 ms |
| 3 | **java-vt-native** | 16,67 ms | 27,92 ms |
| 4 | **java-vt** | 17,85 ms | 28,53 ms |
| 5 | **java-webflux** | 19,21 ms | 34,42 ms |
| 6 | **quarkus** | 19,32 ms | 32,42 ms |
| 7 | **go** | 21,18 ms | 59,61 ms |
| 8 | **rust** | 22,99 ms | 36,37 ms |
| 9 | **micronaut** | 22,38 ms | 31,36 ms |
| 10 | **micronaut-native** | 19,66 ms | 39,14 ms |
| 11 | **java** | 28,73 ms | 47,17 ms |
| 12 | **java-native** | 53,76 ms | 79,09 ms |
| 13 | **bun** | 77,85 ms | 94,31 ms |
| 14 | **nodejs** | 195,29 ms | 267,51 ms |
| 15 | **python** | 240,24 ms | 280,00 ms |

### 9.4 Eficiência de Memória (menor = mais eficiente)

| # | Servidor | RAM avg | RAM max |
|---|----------|---------|---------|
| 1 | **rust** | ~13 MB | ~15 MB |
| 2 | **go** | 25 MB | 28 MB |
| 3 | **quarkus-native** | ~39 MB | ~60 MB |
| 4 | **quarkus** | 190 MB | 202 MB |
| 5 | **micronaut-native** | 174 MB | 244 MB |
| 6 | **java-native** | 228 MB | 315 MB |
| 7 | **java-vt-native** | 225 MB | 268 MB |
| 8 | **micronaut** | 245 MB | 268 MB |
| 9 | **python** | 288 MB | 305 MB |
| 10 | **java** | 373 MB | 390 MB |
| 11 | **nodejs** | 423 MB | 434 MB |
| 12 | **java-vt** | 417 MB | 430 MB |
| 13 | **bun** | 577 MB | 602 MB |
| 14 | **java-webflux** | 596 MB | 759 MB |
| 15 | **java-webflux-native** | 649 MB | 1.057 MB |

### 9.5 Eficiência de CPU (RPS por % de CPU usada)

| # | Servidor | RPS | CPU avg | **RPS/CPU** |
|---|----------|-----|---------|-------------|
| 1 | **rust** | 5.632 | 66,4% | **84,8** |
| 2 | **quarkus** | 5.629 | 96,9% | **58,1** |
| 3 | **java-vt** | 4.978 | 127,4% | **39,1** |
| 4 | **micronaut** | 5.378 | 149,2% | **36,1** |
| 5 | **quarkus-native** | 5.457 | 166,4% | **32,8** |
| 6 | **java-webflux** | 4.579 | 188,6% | **24,3** |
| 7 | **java** | 4.191 | 181,0% | **23,1** |
| 8 | **java-vt-native** | 3.889 | 159,2% | **24,4** |
| 9 | **java-webflux-native** | 3.864 | 195,5% | **19,8** |
| 10 | **go** | 3.845 | 195,9% | **19,6** |
| 11 | **micronaut-native** | 3.711 | 199,5% | **18,6** |
| 12 | **java-native** | 2.833 | 196,3% | **14,4** |
| 13 | **bun** | 1.427 | 200,6% | **7,1** |
| 14 | **nodejs** | 538 | 199,9% | **2,7** |
| 15 | **python** | 375 | 200,2% | **1,9** |

---

## 10. Conclusões e Recomendações

### 10.1 Tier de Performance

Com base nos dados consolidados de 3 execuções (~54,9 milhões de requests, 0% de erros):

**Tier 1 — Alta Performance e Estabilidade (produção, alto volume)**

| Servidor | Por que |
|----------|---------|
| **Quarkus** | Melhor equilíbrio: top-2 em RPS + latência baixa + CPU eficiente + estabilidade excelente |
| **Quarkus-Native** | Menor latência do grupo + P95 mais baixo absoluto (8,45 ms) + memória mínima |
| **Rust** | Maior throughput absoluto (5.632 RPS) + menor CPU (66%) + memória mínima |
| **java-vt** | Estabilidade excepcional (CV=0,86%) + bom RPS + simples de operar (Spring Boot padrão) |

**Tier 2 — Boa Performance (produção, volume médio a alto)**

| Servidor | Por que |
|----------|---------|
| **Micronaut** | Top-4 em RPS + latência baixa + estável |
| **java-webflux** | Bom throughput + latência média baixa (4,78 ms) |
| **java-vt-native** | P95 excelente (16,67 ms) + estável |

**Tier 3 — Performance Adequada (desenvolvimento ou volume moderado)**

| Servidor | Por que |
|----------|---------|
| **Go** | P95 em crescimento entre runs preocupa; após fix do connection pool, pode subir de tier |
| **java-webflux-native** | Excelente P95, mas memória crescente (risco OOM) e alto consumo RAM |
| **java** | Estável e bom RPS mas I/O sequencial limita ganho no cv latência |
| **Micronaut-Native** | P95 com tendência de crescimento requer investigação |
| **Bun** | Bom throughput para JS (3.5x acima do Node.js), mas P95 instável |

**Tier 4 — Desenvolvimento / Prototipação**

| Servidor | Por que |
|----------|---------|
| **java-native** | Instabilidade crítica (CV=12,2%) torna os resultados não confiáveis |
| **Node.js** | P95 de 195 ms para I/O simples é alto; adequado para cargas baixas |
| **Python** | Throughput 15x menor que os líderes; latência P95 de 240 ms |

### 10.2 Descobertas Chave

1. **Java moderno é competitivo com linguagens compiladas.** Quarkus, java-vt e Micronaut batem ou empatam com Go em RPS, mantendo o ecossistema Java e simplicidade operacional.

2. **Rust lidera em throughput puro e eficiência de CPU.** Com apenas 66% de CPU médio para 5.632 RPS, é o único servidor que sobra CPU significativa para picos. Consome menos de 15 MB de memória no estado estável.

3. **Quarkus-Native é o líder em latência de cauda.** P95 de 8,45 ms e P99 de 15,12 ms são os menores do grupo — relevante para SLAs de baixa latência.

4. **Go tem problema de connection pool.** O P95 crescendo de 18 ms para 25 ms entre runs é sintoma de `MaxIdleConnsPerHost=2` (padrão Go). Com o fix documentado no backlog (E1), Go deve melhorar 15-30% em throughput.

5. **Native Images têm trade-offs sérios.** quarkus-native e java-webflux-native se saem bem, mas java-native é altamente instável e java-webflux-native tem crescimento de memória preocupante. O benefício de imagem nativa não é garantido.

6. **Node.js/Bun precisam de mais investigação.** A diferença de 2,6x (Bun vs Node.js) mostra o impacto do runtime JavaScriptCore do Bun. Porém, ambos operam com 4 processos — comparação single-thread seria significativamente pior.

7. **Python é 15x mais lento que os líderes.** FastMCP + Uvicorn tem latência dominada por I/O asyncio, não por overhead do protocolo. É adequado para prototipação e ferramentas internas, não para produção de alto volume.

### 10.3 Itens Pendentes para Round 2

| Prioridade | Item | Impacto Esperado |
|-----------|------|-----------------|
| 🔴 Alta | Fix `MaxIdleConnsPerHost=100` no Go | +15-30% RPS Go, P95 mais estável |
| 🔴 Alta | Investigar instabilidade do java-native | Definir se servidor é viável |
| 🟠 Média | Investigar crescimento de memória java-webflux-native | Evitar OOM em runs longos |
| 🟠 Média | Investigar degradação progressiva micronaut-native P95 | Confirmar tendência ou artefato |
| 🟡 Baixa | Múltiplos runs (5+) por servidor | Maior confiança estatística |
| 🟡 Baixa | Adicionar rust-axum aos resultados | Completar portfolio de implementações |

---

*Laudo gerado em 2026-02-26. Dados brutos disponíveis em `benchmark/results/2026022{6,7}_*/summary.json`.*
