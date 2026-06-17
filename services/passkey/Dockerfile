FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY pkg/ pkg/
COPY services/passkey/ services/passkey/
WORKDIR /app/services/passkey
RUN go mod tidy && go build -o /server ./cmd/server/

FROM alpine:3.19
RUN adduser -D appuser
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /server /server
USER appuser
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8086/health || exit 1
CMD ["/server"]
