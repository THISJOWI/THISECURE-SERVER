FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY pkg/ pkg/
COPY services/password/ services/password/
WORKDIR /app/services/password
RUN go mod tidy && go build -o /server ./cmd/server/

FROM alpine:3.19
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /server /server
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8084/health || exit 1
CMD ["/server"]
