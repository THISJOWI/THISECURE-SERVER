.PHONY: build test test-integration test-all vet clean dev

SERVICES := note otp password

build:
	@for svc in $(SERVICES); do \
		echo "Building $$svc..."; \
		cd services/$$svc && go build -o server ./cmd/server/ && cd ../..; \
	done

test:
	go test ./pkg/... -count=1
	@for svc in $(SERVICES); do \
		echo "Testing $$svc..."; \
		cd services/$$svc && go test -count=1 ./... && cd ../..; \
	done

test-integration:
	go test -tags=integration ./services/... -count=1 -v

test-all: test test-integration

vet:
	go vet ./pkg/...
	@for svc in $(SERVICES); do \
		cd services/$$svc && go vet ./... && cd ../..; \
	done

clean:
	@for svc in $(SERVICES); do \
		rm -f services/$$svc/server; \
	done

dev:
	@echo "Start infra: docker compose up -d"
	@echo "Run services:"
	@for svc in $(SERVICES); do \
		echo "  cd services/$$svc && go run ./cmd/server/ &"; \
	done
