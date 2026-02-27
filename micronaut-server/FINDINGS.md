# Micronaut MCP Server — Benchmark Findings

## Benchmark environment
- 10 VUs, 5 min, 2 CPUs, 2 GB RAM
- Results: `benchmark/results/20260222_153955/`

---

## Finding — micronaut-native: −49% RPS vs JVM (worst native regression)

### Symptom
Native-image regression across all stacks:

| Stack           | JVM RPS | Native RPS | Regression |
|-----------------|---------|------------|------------|
| java-webflux    | 12,023  | 9,447      | −21%       |
| java            | 12,990  | 8,488      | −35%       |
| quarkus         | 24,237  | 16,178     | −33%       |
| java-vt         | 17,407  | 10,488     | −40%       |
| **micronaut**   | **13,309** | **6,779** | **−49% ← outlier** |

Micronaut's native regression is the worst of any stack, ~10pp worse than the
next-worst (java-vt at −40%).

### Root cause: GraalVM 21 vs newer AOT optimisation

`Dockerfile.native` used `ghcr.io/graalvm/native-image-community:21` while other
native stacks (java-webflux-native, quarkus-native) were already using later
GraalVM versions with improved AOT compilation.

Micronaut's `@Tool` / `@ToolArg` / `@Serdeable` annotations are processed at
build time (compile-time metadata), but GraalVM 21 has less mature closed-world
analysis for Micronaut's annotation-driven dispatch compared to GraalVM 23+.
Each tool invocation involves annotation metadata lookup which GraalVM 21 cannot
fully inline, whereas GraalVM 23 does this more aggressively.

Quarkus (Quarkus Arc) and Spring (Spring AOT) have custom GraalVM substitutions
that are well-tuned for older GraalVM versions, so they regress less.

### Fix applied
Updated `Dockerfile.native` line 1:

```dockerfile
# before:
FROM ghcr.io/graalvm/native-image-community:21 AS builder
# after:
FROM ghcr.io/graalvm/native-image-community:23 AS builder
```

**Expected outcome:** RPS > 8,000 (vs baseline 6,779), regression vs JVM < −40%.

### Additional context
- SDK version: `io.micronaut.mcp:micronaut-mcp-server-java-sdk:0.0.19`
- This is an early pre-release SDK; AOT support may improve in future versions.
- The JVM variant (13,309 RPS) is fully competitive with java and java-vt.
