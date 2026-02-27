# Java Virtual Threads MCP Server — Benchmark Findings

## Benchmark environment
- 10 VUs, 5 min, 2 CPUs, 2 GB RAM, sleep(0.05) por iteração
- Results: `benchmark/results/20260223_143650/`

---

## Finding 1 — Virtual Threads são mais lentos que threads tradicionais neste workload

### Sintoma
| Server | RPS | Avg | p95 | CPU avg | MEM avg |
|--------|----:|----:|----:|--------:|--------:|
| Java JVM (MVC) | **4,018** | **0.220ms** | **0.459ms** | 55.4% | 237 MB |
| **Java VT JVM** | **3,857** | **0.335ms** | **0.771ms** | 47.8% | 283 MB |

Java VT é **4% mais lento em RPS** e **52% mais lento em latência avg** que o Java MVC
com threads tradicionais, usando o mesmo código de tool (`McpToolsService` idêntico).

### Causa raiz: Virtual Threads não têm benefício em workloads CPU-bound

**O problema fundamental**: Virtual Threads (Project Loom) são projetadas para workloads
**I/O-bound**, não **CPU-bound**.

O benefício do VT é: quando uma VT bloqueia em I/O (chamada JDBC, HTTP, sleep), ela **cede
o carrier thread** (thread OS) para outra VT executar. Isso permite N threads OS servirem
M >> N VTs bloqueadas em I/O.

Neste benchmark, **nenhuma das 5 tools faz I/O**. Todas são CPU-bound:
- `calculate_fibonacci`: loops numéricos puros
- `process_json_data`: manipulação de strings em memória
- `simulate_database_query`: scan de lista em memória
- `allocate_records`: alocação + filtragem em memória
- `text_search`: scanning de corpus em memória

Uma VT CPU-bound **nunca cede o carrier thread voluntariamente**. Ela roda até completar,
exatamente como uma thread OS. O overhead do scheduler VT (ForkJoinPool) é adicionado
sem nenhum ganho.

### Overhead do scheduler VT (medido)
A diferença de latência avg entre java e java-vt por tool:

| Tool | Java avg | VT avg | Overhead VT |
|------|--------:|-------:|:-----------:|
| `calculate_fibonacci` | 0.264ms | 0.348ms | +84µs |
| `process_json_data` | 0.245ms | 0.327ms | +82µs |
| `allocate_records` | 0.263ms | 0.354ms | +91µs |
| `simulate_database_query` | 0.359ms | 0.611ms | +252µs |
| `text_search` | 0.463ms | 0.751ms | +288µs |

O overhead base é **~85µs por request** para tools simples. Para tools com mais trabalho
(`simulate_database_query`, `text_search`), o overhead é proporcionalmente maior, sugerindo
que o ForkJoinPool do carrier compete com o código de aplicação pelos mesmos CPUs.

### Implementação está correta

A configuração `spring.threads.virtual.enabled=true` em `application.properties` é a forma
canônica de habilitar VT no Spring Boot 3.2+. Ela instrui o Tomcat a usar um executor de
Virtual Threads em vez do thread pool clássico.

O problema não é implementação incorreta — é **escolha de tecnologia inapropriada para o
workload**.

### Quando VT realmente ajuda

VT seria superior ao MVC tradicional em cenários como:

```
Cenário hipotético: 1000 VUs simultâneos fazendo queries reais ao banco

Com threads tradicionais:
- Tomcat pool padrão = 200 threads
- 800 requests aguardam no accept queue
- Latência elástica conforme filas crescem

Com Virtual Threads:
- 1 VT por request (sem limite prático)
- Cada VT bloqueia no JDBC → cede carrier thread → outra VT executa
- 1000 VTs ativas em apenas 2–4 carrier threads OS
- Sem fila de accept, latência baixa e estável
```

Em nosso benchmark (10 VUs, CPU-bound), tanto 10 threads OS quanto 10 VTs resolvem
identicamente — com VT apenas adicionando overhead de scheduler.

### Action
Documentado. Para um benchmark com ferramentas I/O-bound (ex: chamadas reais a banco,
HTTP externo), refazer o teste com java-vt esperaria mostrar vantagem significativa sobre
java a partir de ~50+ VUs.

---

## Finding 2 — java-vt-native tem o pior desempenho entre os nativos Java

### Sintoma
| Server | RPS | Avg | p95 |
|--------|----:|----:|----:|
| Quarkus Native | 3,978 | 0.285ms | 0.763ms |
| WebFlux Native | 3,912 | 0.324ms | 0.617ms |
| Java Native | 3,785 | 0.371ms | 0.774ms |
| **Java VT Native** | **3,390** | **0.692ms** | **1.562ms** |
| Micronaut Native | 2,971 | 0.408ms | 0.930ms |

java-vt-native tem latência **2× maior** que java-native com as mesmas tools.

### Causa raiz: suporte experimental de VT no GraalVM Native Image

GraalVM Native Image suporta Virtual Threads desde a versão 21, mas com limitações:
1. O scheduler de VT (ForkJoinPool com work-stealing) é compilado estaticamente sem
   as otimizações de runtime que o JIT aplica ao código do scheduler
2. O mecanismo de **continuation** (salvar/restaurar stack de VT) usa implementação
   baseada em `Unsafe.park/unpark` no native, com overhead maior que no JVM
3. Com workload CPU-bound, o overhead de continuation é pago sem benefício de
   multiplexação de I/O

O gap java-vt-native vs java-vt-jvm é o maior de todos os pares JVM/native:
- `process_json_data`: JVM 0.327ms vs native 0.742ms (**2.27× mais lento no native**)
- `allocate_records`: JVM 0.354ms vs native 0.859ms (**2.43× mais lento no native**)

Para comparação, o par java-jvm vs java-native tem gap de apenas 1.59×–1.80×.

### Action
Documentado. Java-vt em native é a combinação com pior custo-benefício:
paga overhead de VT sem benefício de I/O multiplexing, e paga overhead do AOT sem
benefício do JIT para otimizar o scheduler.
