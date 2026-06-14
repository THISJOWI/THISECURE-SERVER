FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY pkg/ pkg/
COPY services/note/ services/note/
WORKDIR /app/services/note
RUN go mod tidy && go build -o /server ./cmd/server/

FROM alpine:3.19
RUN apk --no-cache add ca-certificates
COPY --from=builder /server /server
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8083/health || exit 1
CMD ["/server"]
