FROM rust:slim AS builder
WORKDIR /app

# Copy the patched SDK first — required by [patch.crates-io] in Cargo.toml
COPY rmcp-patched/ rmcp-patched/

# Cache dependencies: copy manifests and build with a dummy main
COPY Cargo.toml .
RUN mkdir src && echo 'fn main() {}' > src/main.rs
RUN cargo build --release
RUN rm src/main.rs

# Build the real binary
COPY src/ src/
RUN touch src/main.rs && cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/rust-mcp-server /usr/local/bin/
EXPOSE 8095
CMD ["rust-mcp-server"]
