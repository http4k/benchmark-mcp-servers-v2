FROM golang:1.23-alpine AS builder

WORKDIR /app

# Copiar todo o código (necessário para go mod tidy detectar imports)
COPY go.mod ./
COPY main.go .

# Baixar dependências e gerar go.sum
RUN go mod tidy
RUN go mod download

# Compilar binary estático
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o server .

# Imagem final
FROM alpine:latest

RUN apk --no-cache add ca-certificates

WORKDIR /root/

# Copiar binary da imagem builder
COPY --from=builder /app/server .

# Expor porta
EXPOSE 8081

# Executar
CMD ["./server"]
