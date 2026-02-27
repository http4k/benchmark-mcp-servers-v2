# FINDINGS — rust-server (rmcp SDK)

## Contexto

Servidor Rust usando o SDK oficial `rmcp` v0.16. Implementa as 3 tools do benchmark
(`search_products`, `get_user_cart`, `checkout`) sobre o transpor Streamable HTTP do SDK.

---

## Gargalo Original — Transporte SSE hardcoded (rmcp v0.16)

### Sintoma (benchmark antes do patch)

```
search_products  avg=0.75ms   p95=1.49ms   ← tool rápida (só ZADD Redis, sem HTTP)
get_user_cart    avg=41.04ms  p95=41.84ms  ← anômalo: 41ms fixo
checkout         avg=41.02ms  p95=41.83ms  ← anômalo: 41ms fixo
RPS total: 770 req/s
```

O `search_products` é fast porque usa apenas Redis. As outras duas tools chamam a `api-service`
via HTTP e Redis. O `~41ms fixo` não vinha de I/O real (a api-service responde em <2ms) — vinha
do **overhead do transporte SSE** do SDK.

### Causa raiz

Em `rmcp` v0.16, o handler `StreamableHttpService::handle_post` no modo stateless
**sempre** retorna `text/event-stream`, mesmo para respostas simples request-response:

```rust
// crates/rmcp/src/transport/streamable_http_server/tower.rs — ANTES do patch
ClientJsonRpcMessage::Request(mut request) => {
    let (transport, receiver) = OneshotTransport::<RoleServer>::new(...);
    let service = serve_directly(service, transport, None);
    tokio::spawn(async move { let _ = service.waiting().await; });

    // ← PROBLEMA: sempre SSE, sem alternativa
    let stream = ReceiverStream::new(receiver).map(|message| {
        ServerSseMessage { event_id: None, message: Some(Arc::new(message)), retry: None }
    });
    Ok(sse_stream_response(stream, self.config.sse_keep_alive, ...))
}
```

Isso impõe:
- `Transfer-Encoding: chunked` em toda resposta
- `text/event-stream` com framing `data: {...}\n\n`
- Keep-alive pings a cada 15s mantendo conexões abertas

O overhead total foi medido em **~41ms por request** independente do que a tool faça.

---

## Solução — Patch no rmcp SDK (fork local)

### Estratégia

Clonar o SDK localmente em `rmcp-patched/`, aplicar patch cirúrgico compatível com a
[MCP Streamable HTTP spec (2025-06-18)](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http):

> "The server MUST reply with either **Content-Type: text/event-stream** or **Content-Type: application/json**"

O patch adiciona campo `json_response: bool` ao config — quando `true` e `stateful_mode: false`,
o servidor retorna `application/json` diretamente ao invés de SSE.

### Mudanças no SDK (`tower.rs`)

**1. Campo novo no config (backwards-compatible):**
```rust
pub struct StreamableHttpServerConfig {
    pub sse_keep_alive: Option<Duration>,
    pub sse_retry: Option<Duration>,
    pub stateful_mode: bool,
    pub json_response: bool,   // NEW — false por default, preserva comportamento original
    pub cancellation_token: CancellationToken,
}
```

**2. Bifurcação no bloco stateless de `handle_post`:**
```rust
// DEPOIS do patch (Refinado conforme revisão no PR #683)
if self.config.json_response {
    // Await única resposta com suporte a cancellation via tokio::select! (application/json)
    let cancel = self.config.cancellation_token.child_token();
    match tokio::select! {
        res = receiver.recv() => res,
        _ = cancel.cancelled() => None,
    } {
        Some(message) => {
            tracing::info!(?message);
            let body = serde_json::to_vec(&message)?;
            Ok(Response::builder()
                .header(CONTENT_TYPE, JSON_MIME_TYPE)
                .body(Full::new(Bytes::from(body)).boxed()))
        }
        None => Err(internal_error_response("empty response")(...))
    }
} else {
    // CAMINHO ORIGINAL PRESERVADO INTEGRALMENTE — SSE stream
    let stream = ReceiverStream::new(receiver).map(|message| {
        ServerSseMessage { event_id: None, message: Some(Arc::new(message)), retry: None }
    });
    Ok(sse_stream_response(stream, self.config.sse_keep_alive, ...))
}
```

### Integração no rust-server

**`Cargo.toml`** — redireciona `rmcp` para o fork local:
```toml
[patch.crates-io]
rmcp = { path = "rmcp-patched/crates/rmcp" }
```

**`src/main.rs`** — ativa o modo JSON:
```rust
StreamableHttpServerConfig {
    stateful_mode: false,
    json_response: true,   // ← patch ativado
    ..Default::default()
}
```

**`Dockerfile`** — copia o SDK antes do build de deps:
```dockerfile
COPY rmcp-patched/ rmcp-patched/   # ← necessário para [patch.crates-io]
COPY Cargo.toml .
RUN cargo build --release
```

---

## Prova de Backward Compatibility

O patch é **backwards-compatible por construção**:

| Configuração | Código afetado | Comportamento |
|---|---|---|
| `stateful_mode: true` | ❌ nenhum — bloco `if stateful_mode` não foi tocado | SSE igual ao original |
| `stateful_mode: false` + `json_response: false` (default) | `else { ... }` — código original preservado integralmente | SSE igual ao original |
| `stateful_mode: false` + `json_response: true` | novo `if json_response` | JSON direto (novo) |

### Prova empírica — SSE path preservado

Teste realizado com uma tool `ping` sem I/O + `json_response: false` + `sse_keep_alive: None`:

```
POST /mcp  →  json_response: false

HTTP/1.1 200 OK
content-type: text/event-stream            ← SSE preservado ✅
cache-control: no-cache
transfer-encoding: chunked

data: {"jsonrpc":"2.0","id":1,"result":{   ← framing SSE correto ✅
  "content":[{"type":"text","text":"{\"pong\":true}"}],
  "isError":false
}}\n\n
0\r\n\r\n                                  ← stream fecha após resposta ✅
```

### Prova empírica — JSON path (patch ativo)

```
POST /mcp  →  json_response: true

HTTP/1.1 200 OK
content-type: application/json             ← JSON direto ✅
content-length: 142                        ← resposta completa, sem chunked ✅

{"jsonrpc":"2.0","id":1,"result":{...}}   ← sem framing SSE ✅
```

---

## Resultado do Benchmark com o Patch e Otimizações de Pool

**Configuração:** 50 VUs · 5 min · 2 CPU · 2 GB RAM (Tools convertidas para I/O-bound reais)

| Métrica | Antes (SSE + Multiplex) | Depois (JSON + Deadpool) | Δ |
|---|---|---|---|
| **RPS** | 770 | **2.378** | **+208%** |
| `search_products` avg | N/A | 54.91 ms | (Redis ZREVRANGE + chamada HTTP real) |
| `get_user_cart` avg | **41.04 ms** | **34.71 ms** | (Redis paralelos HGETALL/LRANGE) |
| `checkout` avg | **41.02 ms** | **18.89 ms** | **-53% 🎉** (Pipeline INCR/RPUSH/ZADD) |
| CPU avg | 8.2% | 30.9% | Escalou perfeitamente usando todo o fôlego |
| Memória avg | 25 MB | 21.6 MB | Eficiência RAM extrema mantida |
| Erros | 0 | 0 | — |

### Contexto: Evolução do RPS (770 → 1.139 → 2.378)

1. **Apenas com o recuso do SSE (JSON direto):** O servidor subiu de 770 para 1.139 RPS e travou o ganho. O overhead de transporte havia sumido, mas esbarramos no I/O interno.
2. **Troca do Redis ConnectionManager por Deadpool (Pool de Conexões):** Com a rede livre das travas do SSE, o gargalo flutuou para a conexão única multiplexada do `redis::aio::ConnectionManager` no Rust.
3. **Rust Livre:** Ao trocar para a engine do `deadpool-redis`, usar Pipeling e paralelizar lógicas em `tokio::spawn`, a barreira foi destruída e o Rust decolou para **~2.400 RPS** com incríveis **21MB de RAM**, solidificando-se no pódio de eficiência do benchmark junto com Go.

---

## Status do PR no Upstream (PR #683)

O Pull Request **[#683](https://github.com/modelcontextprotocol/rust-sdk/pull/683)** foi aberto no repositório oficial `modelcontextprotocol/rust-sdk`, alinhado com o desenvolvimento do SDK:

- Completa a paridade no lado servidor para o suporte nativo adicionado no **PR #540** (client-side).
- Totalmente **Backwards-compatible**: `json_response: false` (default) preserva o comportamento SSE intacto.
- **Refinado via Code Review:** Com as sugestões afiadas dos mantenedores atendidas, a branch de submissão adotou o modelo limpo via `tokio::select!` em conjunto com logs de `tracing::info!` para blindar o loop contra deadlocks no shutdown.

---

## Estrutura do Fork Local

```
rust-server/
  rmcp-patched/            ← git clone --depth=1 modelcontextprotocol/rust-sdk
    crates/
      rmcp/
        src/transport/streamable_http_server/
          tower.rs         ← arquivo patched
  src/main.rs              ← json_response: true
  Cargo.toml               ← [patch.crates-io] rmcp = { path = "rmcp-patched/crates/rmcp" }
  Dockerfile               ← COPY rmcp-patched/ antes do cargo build
```

Para abrir o PR upstream: o patch em `tower.rs` é o único arquivo modificado no SDK.
