FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o relay-server .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /app/relay-server /relay-server
EXPOSE 8080
CMD ["/relay-server"]
