# Root-level Dockerfile for Render.com (Docker web service mode).
# When Render uses "Dockerfile" as the build type, it runs docker build
# with the repository root as the context. This file bridges to the
# relay-server source in relay-server/.

FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY relay-server/go.mod relay-server/go.sum ./
RUN go mod download
COPY relay-server/main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o relay-server .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /app/relay-server /relay-server
EXPOSE 8080
CMD ["/relay-server"]
