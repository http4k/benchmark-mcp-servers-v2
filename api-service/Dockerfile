FROM golang:1.24-alpine AS builder
WORKDIR /app
COPY go.mod .
COPY main.go .
RUN go build -o api-service .

FROM alpine:3.21
WORKDIR /app
COPY --from=builder /app/api-service .
EXPOSE 8100
CMD ["./api-service"]
