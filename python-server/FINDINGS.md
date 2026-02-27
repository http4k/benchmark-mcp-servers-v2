# Python MCP Server — Findings & History

## Arquitetura

- **Runtime:** CPython 3.11
- **Framework:** Starlette + `mcp` SDK (`FastMCP`, stateless HTTP)
- **Servidor ASGI:** Uvicorn (padrão) / Granian (variante `Dockerfile.granian`)
- **Porta:** 8082 (python) / 8093 (python-granian)
- **Modo MCP:** `FastMCP("BenchmarkPythonServer", stateless_http=True, json_response=True)`

---

## Configuração do servidor ASGI

### Uvicorn (padrão)
```
uvicorn main:app --host 0.0.0.0 --port 8082 --workers 4 --loop uvloop
```
- `--workers 4`: 4 processos OS independentes, cada um com seu próprio event loop
- `--loop uvloop`: substitui o event loop asyncio padrão pelo uvloop (implementado em Cython/libuv), reduz overhead de I/O

### Granian (variante)
```
granian --interface asgi --host 0.0.0.0 --port 8082 --workers 4 main:app
```
- Servidor ASGI escrito em Rust, usa Tokio como runtime de I/O
- Suporta ASGI lifespan → o `_http_client` compartilhado (httpx) funciona normalmente

---

## Padrão canônico do FastMCP SDK (python-sdk README)

A investigação da documentação oficial do SDK em
https://github.com/modelcontextprotocol/python-sdk confirmou que o padrão
canônico para Streamable HTTP é:

```python
mcp = FastMCP("App", stateless_http=True, json_response=True)

@asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run():
        yield

app = FastAPI(lifespan=lifespan)
app.mount("/mcp", mcp.streamable_http_app())
```

**Nossas desvios razoáveis:**
- Usamos `Starlette` em vez de `FastAPI` (mais leve; FastMCP já depende de Starlette)
- Montamos em `"/"` em vez de `"/mcp"` porque `streamable_http_app()` expõe `/mcp` internamente
- Adicionamos lifespan do `_http_client` junto ao `mcp.session_manager.run()`

---

## `json_response=True` — resposta JSON plana

```python
mcp = FastMCP("BenchmarkPythonServer", stateless_http=True, json_response=True)
```

Por padrão, o FastMCP retorna respostas no formato SSE (_Server-Sent Events_), mesmo
para requests HTTP simples (stateless). Com `json_response=True`, o servidor retorna
JSON plano sem o framing SSE.

**Impacto:**
- Elimina o overhead de parsing SSE no cliente
- Reduz o tamanho das respostas (sem prefixos `data:`, `event:`, etc.)
- O python-sdk README afirma: _"Use `stateless_http=True` and `json_response=True`
  for optimal scalability"_
- Adicionado em 2026-02-21; benchmark de comparação pendente

---

## Starlette em vez de FastAPI

FastAPI é uma camada sobre Starlette que adiciona:
- Geração automática de schema OpenAPI
- Dependency injection declarativa
- Validação de requests via Pydantic em decorators

Nenhum desses recursos é necessário aqui. O `mcp.streamable_http_app()` já retorna
um app Starlette (FastMCP depende de `starlette` e `sse-starlette`). Usar Starlette
diretamente é mais leve e consistente:

```python
async def health(request):
    return JSONResponse({"status": "ok", "server_type": "python"})

app = Starlette(
    lifespan=lifespan,
    routes=[
        Route("/health", health),
        Mount("/", mcp.streamable_http_app()),
    ],
)
```

A `Route("/health", ...)` tem precedência sobre o `Mount("/", ...)` no roteamento
Starlette, garantindo que o healthcheck funcione corretamente.

---

## Cliente HTTP compartilhado (httpx)

Para a tool `fetch_external_data`, o servidor usa um `httpx.AsyncClient` compartilhado
entre todas as requests, criado no lifespan:

```python
@asynccontextmanager
async def lifespan(app):
    global _http_client
    _http_client = httpx.AsyncClient(timeout=10.0)
    async with mcp.session_manager.run():
        yield
    await _http_client.aclose()
    _http_client = None
```

**Por quê:** criar um `httpx.AsyncClient` por request é caro (cria novo pool de conexões
TCP). Com o cliente compartilhado, conexões são reutilizadas (keep-alive), reduzindo
latência e overhead de handshake.

**Nota de compatibilidade:** como o Uvicorn roda com `--workers 4` (multiprocesso, não
multithread), cada worker tem sua própria instância do `_http_client`. Não há condição
de corrida.

---

## Variante Granian

Criado `Dockerfile.granian` para testar se o servidor ASGI era o gargalo:

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && pip install --no-cache-dir granian
COPY main.py .
EXPOSE 8082
CMD ["granian", "--interface", "asgi", "--host", "0.0.0.0", "--port", "8082", \
     "--workers", "4", "main:app"]
```

**Resultado:** Granian foi marginalmente **pior** que Uvicorn (−12% RPS). O gargalo não é
o servidor ASGI, mas sim o overhead do protocolo MCP / FastMCP dentro de cada request.

---

## Resultados de Benchmark (10 VUs, 5 min, 2 CPUs, 2 GB)

| Data | Configuração | RPS | Avg(ms) | P95(ms) | CPU% | MEM(MB) |
|---|---|---|---|---|---|---|
| 2026-02-20 | python — uvicorn 4 workers, otimizações iniciais | 671 | 7.7 | — | 197% | — |
| 2026-02-21 | python-granian — granian 4 workers | 592 | 9.6 | 27.1 | 192% | 238 |

> CPU% acima de 100% indica uso de múltiplos núcleos (4 workers × ~50% cada).

---

## Observações

- **Gargalo real:** o overhead de criar/processar sessões MCP no Python SDK a cada request.
  O servidor ASGI (Uvicorn vs Granian) tem impacto mínimo.
- **CPU alto (197%):** esperado com 4 workers processando requests CPU-bound (fibonacci)
  e I/O-bound (fetch) simultaneamente.
- **Granian não ajudou:** o Rust I/O runtime do Granian não compensa o overhead Python
  no processamento MCP interno. Uvicorn+uvloop é suficiente.
- **Comparação com JVM:** Python fica ~2.5× abaixo do Quarkus (1648 RPS). O overhead
  interpretado do Python + GIL por worker é o fator limitante.

---

## Referências

- python-sdk README (Streamable HTTP): https://github.com/modelcontextprotocol/python-sdk?tab=readme-ov-file#streamable-http-transport
- FastMCP deployment docs: https://gofastmcp.com/deployment/running-server
- MCP transports spec: https://modelcontextprotocol.io/docs/concepts/transports
