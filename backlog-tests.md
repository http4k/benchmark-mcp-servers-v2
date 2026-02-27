# Plano de Testes — Após Implementação do Backlog de Fairness

Este documento descreve o passo a passo completo para validar e executar o benchmark após todas as mudanças implementadas.

---

## Pré-requisitos

Confirme que as ferramentas estão disponíveis antes de começar:

```bash
docker --version          # >= 24.x
docker compose version    # >= 2.x
k6 version                # >= 0.49
python3 --version         # >= 3.10
curl --version
```

---

## Fase 0 — Rebuild das imagens Docker

Apenas os servidores com código alterado precisam ser reconstruídos.

| Serviço | O que mudou | Tempo estimado |
|---|---|---|
| `go-server` | Transport pool HTTP (E1) | ~1 min |
| `java-server` | I/O paralelo + pipeline Redis (E2+E3) | ~3 min |
| `java-native-server` | Mesmo código do java-server (GraalVM) | ~15 min |
| `java-vt-server` | I/O paralelo + VT executor (E2+E3) | ~3 min |
| `java-vt-native-server` | Mesmo código do java-vt-server (GraalVM) | ~15 min |
| `rust-server` | get_user_cart paralelo (I5) | ~5 min |

### Passo 0.1 — Builds rápidos (JVM + Go + Rust)

Execute primeiro para poder iniciar os testes de sanidade enquanto os nativos compilam.

```bash
cd /home/thiago/Dev/Git/benchmark-mcp-servers

docker compose build \
  go-server \
  java-server \
  java-vt-server \
  rust-server
```

**Resultado esperado:** todos os 4 builds terminam com `Successfully built` / `FINISHED`.

### Passo 0.2 — Builds nativos (GraalVM — demorado)

Execute em paralelo ou em segundo plano. Requer ~4 GB de RAM livre por build.

```bash
# Em paralelo (usa mais RAM, termina mais rápido)
docker compose build java-native-server java-vt-native-server

# Ou sequencial se RAM for limitada
docker compose build java-native-server
docker compose build java-vt-native-server
```

**Resultado esperado:** imagens `*-native-server` criadas. Se falhar por falta de memória, reduza workers do GraalVM no Dockerfile ou faça sequencial.

---

## Fase 1 — Testes de Sanidade Individual

**Objetivo:** garantir que cada servidor alterado inicializa corretamente e processa os 3 tools sem erros antes de qualquer benchmark.

### Setup da infraestrutura

```bash
cd /home/thiago/Dev/Git/benchmark-mcp-servers

docker compose up -d mcp-redis mcp-api-service
sleep 5
docker compose run --rm redis-seeder
```

**Resultado esperado:** `redis-seeder` termina com algo como `Seeded 10000 carts, 1000 histories, 100000 popular scores`.

---

### Teste 1A — Go Server (E1: HTTP pool)

```bash
docker compose up -d go-server
sleep 3

# Health check
curl -s http://localhost:8081/health
```

**Resultado esperado:**
```json
{"status":"ok","server_type":"go"}
```

```bash
# Testar initialize
SESSION=$(curl -si -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  | grep -i 'mcp-session-id:' | awk '{print $2}' | tr -d '\r')

echo "Session: $SESSION"

# Testar search_products
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_products","arguments":{"category":"Electronics","min_price":50.0,"max_price":500.0,"limit":10}}}' \
  | python3 -m json.tool

# Testar get_user_cart
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_user_cart","arguments":{"user_id":"user-00042"}}}' \
  | python3 -m json.tool

# Testar checkout
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"checkout","arguments":{"user_id":"user-00042","items":[{"product_id":42,"quantity":2},{"product_id":1337,"quantity":1}]}}}' \
  | python3 -m json.tool
```

**Resultados esperados:**
- `search_products`: `total_found: 2251`, `products` com 10 itens, `top10_popular_ids` com 10 itens
- `get_user_cart`: `cart.items` com >= 1 item, `recent_history` com 5 entradas
- `checkout`: `status: "confirmed"`, `total > 0`, `items_count: 2`

```bash
docker compose stop go-server
```

---

### Teste 1B — Java Server (E2+E3: I/O paralelo + pipeline) — CRÍTICO

Este é o teste mais importante. O código foi completamente reescrito com `CompletableFuture`, portanto erros de runtime não aparecem na compilação.

```bash
docker compose up -d java-server
# Aguarda JVM inicializar (Spring Boot demora ~15–20s)
sleep 20

# Aguarda health
until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do
  echo "Aguardando Java..."; sleep 3
done
echo "Java pronto"

# Obtém session ID
SESSION=$(curl -si -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  | grep -i 'mcp-session-id:' | awk '{print $2}' | tr -d '\r')

# Testar os 3 tools com o mesmo padrão do Teste 1A (porta 8080)
# search_products
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_products","arguments":{"category":"Electronics","min_price":50.0,"max_price":500.0,"limit":10}}}' \
  | python3 -m json.tool

# get_user_cart
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_user_cart","arguments":{"user_id":"user-00042"}}}' \
  | python3 -m json.tool

# checkout (testa pipeline Redis: INCR + RPUSH + ZINCRBY)
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"checkout","arguments":{"user_id":"user-00042","items":[{"product_id":42,"quantity":2},{"product_id":1337,"quantity":1}]}}}' \
  | python3 -m json.tool

# Verificar que o pipeline Redis funcionou: INCR deve ter incrementado
docker compose exec -T mcp-redis redis-cli GET bench:ratelimit:user-00042
# Resultado esperado: "1" (ou mais se chamado múltiplas vezes)
```

**Pontos críticos a verificar no Java:**
- Sem `NullPointerException` nos logs: `docker compose logs java-server | grep -i exception`
- `checkout` retorna `rate_limit_count >= 1` (confirma que pipeline Redis executou)
- `search_products` retorna `total_found: 2251`
- `get_user_cart` retorna `recent_history` com exatamente 5 entradas

```bash
docker compose stop java-server
```

---

### Teste 1C — Java-VT Server (E2+E3 com Virtual Threads)

Mesmo roteiro do Teste 1B, mas na porta `8087`.

```bash
docker compose up -d java-vt-server
sleep 20

until curl -sf http://localhost:8087/actuator/health > /dev/null 2>&1; do
  echo "Aguardando Java-VT..."; sleep 3
done

SESSION=$(curl -si -X POST http://localhost:8087/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  | grep -i 'mcp-session-id:' | awk '{print $2}' | tr -d '\r')

# Testar os 3 tools (substituir porta 8087 nos curls do Teste 1B)
# ...

# Verificar no log que virtual threads estão sendo criadas
docker compose logs java-vt-server | head -30

docker compose stop java-vt-server
```

**Resultado esperado:** mesmo que 1B. O `server_type` nos responses deve retornar `"java-vt"`.

---

### Teste 1D — Rust Server (I5: get_user_cart paralelo)

```bash
docker compose up -d rust-server
sleep 3

curl -s http://localhost:8095/health

SESSION=$(curl -si -X POST http://localhost:8095/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  | grep -i 'mcp-session-id:' | awk '{print $2}' | tr -d '\r')

# get_user_cart — o que foi alterado (LRANGE agora paralelo ao HTTP)
curl -s -X POST http://localhost:8095/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_user_cart","arguments":{"user_id":"user-00042"}}}' \
  | python3 -m json.tool

docker compose stop rust-server
```

**Resultado esperado:** `recent_history` com 5 entradas, sem panic/error nos logs (`docker compose logs rust-server`).

---

## Fase 2 — Smoke Benchmark (validação do pipeline completo)

**Objetivo:** rodar um benchmark real com 3 servidores representativos para confirmar que o `run_benchmark.sh` reformulado funciona de ponta a ponta: reset Redis, warmup com tool calls, k6 com user_id por VU, consolidação com seção `infra`.

**Duração estimada:** ~25 minutos (3 servidores × ~7 min cada + reset Redis por servidor)

```bash
cd /home/thiago/Dev/Git/benchmark-mcp-servers/benchmark

./run_benchmark.sh go java rust
```

### O que observar durante a execução

**Reset Redis entre servidores:**
```
[INFO] Resetting Redis state (flush + re-seed)...
[OK]   Redis reset and re-seeded
```
Deve aparecer antes de cada servidor. Se travar aqui, o `redis-seeder` tem algum problema.

**Warmup com tool calls:**
```
[INFO] Warming up go (5 init + 3 sessions per tool)...
[OK]   Warmup complete (5 init + 9 tool sessions)
```

**k6 sem threshold violations:**
```
✓ http_req_failed.........: 0.00% ✓ 0 ✗ 0
```
Se aparecer `✗` no threshold de erros (>5%), investigar os logs do servidor.

**Checks k6 devem passar todos:**
```
✓ search_products ok
✓ get_user_cart ok
✓ checkout ok
✓ tools/list ok
```

### Validação do output gerado

Após o smoke, verificar o `summary.json`:

```bash
# Localiza o diretório do run mais recente
LATEST=$(ls -td benchmark/results/*/ | head -1)
echo "Resultados em: $LATEST"

# Verifica seção infra (N4) — deve estar presente
python3 -c "
import json
with open('${LATEST}summary.json') as f:
    s = json.load(f)
print('infra presente:', 'infra' in s)
print('config.warmup_seconds:', s['config'].get('warmup_seconds'))
print('Servidores:', list(s['servers'].keys()))
print('Rankings RPS:', s.get('rankings', {}).get('rps', [])[:3])
"
```

**Resultados esperados:**
```
infra presente: True
config.warmup_seconds: 60
Servidores: ['go', 'java', 'rust']
Rankings RPS: ['rust', 'go', 'java']   ← ordem aproximada esperada
```

### Verificar que user_id está variando (E4)

```bash
# Inspeciona o log do k6 console de um servidor
grep -o 'user-[0-9]*' "${LATEST}go/k6_console.log" | sort -u | head -10
# Esperado: user-00001, user-00002, ... user-00050 (VUs 1 a 50)
```

---

## Fase 3 — Benchmark Completo Comparativo

**Objetivo:** obter resultados finais para todos os 15 servidores com os 13 itens do backlog implementados, e comparar com os runs anteriores (`20260225_155402` e `20260225_173220`).

**Duração estimada:** ~2h por run (15 servidores × ~8 min cada + reset Redis)

### Passo 3.1 — Run único de validação

```bash
cd /home/thiago/Dev/Git/benchmark-mcp-servers/benchmark

./run_benchmark.sh
```

### Passo 3.2 — Dois runs com shuffle (recomendado)

Gera dados suficientes para análise de variância com ordem aleatória diferente a cada run:

```bash
./run_benchmark.sh --runs 2 --shuffle
```

### Passo 3.3 — Análise dos resultados

```bash
# Compara os dois novos runs entre si
python3 benchmark/extract_stats.py \
  benchmark/results/<TIMESTAMP_1>/summary.json \
  benchmark/results/<TIMESTAMP_2>/summary.json

# Compara com os runs pré-backlog (baseline)
python3 benchmark/generate_table.py \
  benchmark/results/20260225_173220/summary.json \
  benchmark/results/<NOVO_TIMESTAMP>/summary.json
```

---

## Análise de Resultados Esperados

### Rankings esperados após o backlog

| Posição | Servidor | Justificativa |
|---|---|---|
| 1–2 | `rust` ou `java-webflux-native` | Compilado + async, sem JVM overhead |
| 3–4 | `go` | Após E1 (pool HTTP), deve subir ~5 posições vs baseline |
| 4–6 | `quarkus-native`, `micronaut-native` | GraalVM + reactive |
| 6–8 | `java-webflux`, `java-vt` | VT com I/O paralelo explícito (E3) |
| 8–10 | `bun`, `nodejs` | 4 workers, Promise.all |
| 10–12 | `java`, `java-vt` | Após E2+E3, esperada melhora ~30% vs baseline |
| 12–14 | `python`, `micronaut`, `quarkus` | Async mas runtime mais pesado |
| 15 | `java-native` | Compila sem warmup JIT mas menor throughput que WebFlux native |

### Deltas esperados vs baseline (runs de 25/02)

| Servidor | Métrica | Baseline | Esperado | Mudança |
|---|---|---|---|---|
| `go` | RPS | ~1930 | ~2800–3200 | **+45–65%** (E1: pool HTTP) |
| `java` | latência avg | ~31ms | ~18–22ms | **−30–40%** (E2+E3: paralelo) |
| `java-vt` | latência avg | ~30ms | ~16–20ms | **−35–45%** (E2+E3: VT paralelo) |
| `rust` | latência p95 | ~28ms | ~22–26ms | **−10–15%** (I5: LRANGE paralelo) |
| todos | variância entre runs | alta | baixa | (I1: Redis reset) |

> **Se Go não subir ~40%+**: o problema pode ser outro gargalo além do pool. Verificar se o API service (0.5 CPU) está saturando.
>
> **Se Java não melhorar**: verificar nos logs se `CompletableFuture` está lançando exceção e fallback para execução sequencial.

### Sinais de problema

| Sintoma | Causa provável | Ação |
|---|---|---|
| Error rate > 5% no Java/Java-VT | `ClassCastException` no pipeline Redis ou NPE no `CompletableFuture` | `docker compose logs java-server \| grep -i exception` |
| Go com RPS similar ao baseline | Pool HTTP não estava sendo o gargalo; api-service pode ser | Monitorar CPU do `mcp-api-service` durante o run |
| Reset Redis travando entre servidores | `redis-seeder` com timeout ou bug | Rodar `docker compose run --rm redis-seeder` manualmente |
| k6 `check: get_user_cart ok` falhando | `recent_history` com tamanho != 5 após reset | Verificar se seeder popula as histories corretamente |
| Rust `get_user_cart` com panic nos logs | Borrow checker em `tokio::spawn` com pool clonado | `docker compose logs rust-server \| grep -i panic` |

---

## Referência Rápida — Portas dos Servidores

| Servidor | Porta | Health endpoint |
|---|---|---|
| `java` | 8080 | `/actuator/health` |
| `go` | 8081 | `/health` |
| `python` | 8082 | `/health` |
| `nodejs` | 8083 | `/health` |
| `java-native` | 8084 | `/actuator/health` |
| `quarkus` | 8085 | `/q/health` |
| `quarkus-native` | 8086 | `/q/health` |
| `java-vt` | 8087 | `/actuator/health` |
| `java-vt-native` | 8088 | `/actuator/health` |
| `java-webflux` | 8089 | `/actuator/health` |
| `java-webflux-native` | 8090 | `/actuator/health` |
| `micronaut` | 8091 | `/health` |
| `micronaut-native` | 8092 | `/health` |
| `bun` | 8094 | `/health` |
| `rust` | 8095 | `/health` |

---

## Checklist Final

```
Fase 0 — Builds
  [ ] go-server rebuilt
  [ ] java-server rebuilt
  [ ] java-vt-server rebuilt
  [ ] rust-server rebuilt
  [ ] java-native-server rebuilt (opcional para smoke test)
  [ ] java-vt-native-server rebuilt (opcional para smoke test)

Fase 1 — Sanidade
  [ ] Go: 3 tools respondem corretamente
  [ ] Java: 3 tools respondem, sem exceção nos logs, ratelimit incrementou
  [ ] Java-VT: 3 tools respondem, server_type = "java-vt"
  [ ] Rust: get_user_cart retorna 5 históricos, sem panic nos logs

Fase 2 — Smoke (go + java + rust)
  [ ] reset_redis aparece antes de cada servidor
  [ ] warmup completa sem travar
  [ ] k6 error rate < 5% nos 3 servidores
  [ ] todos os checks k6 passando
  [ ] summary.json tem seção "infra"
  [ ] config.warmup_seconds = 60

Fase 3 — Full benchmark
  [ ] Run completo com 15 servidores concluído
  [ ] Go subiu no ranking vs baseline
  [ ] Java/Java-VT melhoraram latência vs baseline
  [ ] Dois runs com --shuffle para análise de variância
```
