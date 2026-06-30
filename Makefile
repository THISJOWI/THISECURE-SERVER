.PHONY: build test test-integration test-all vet clean dev

SERVICES := note otp password
NESTJS_SERVICES := messaging

build:
	@for svc in $(SERVICES); do \
		echo "Building $$svc..."; \
		cd services/$$svc && go build -o server ./cmd/server/ && cd ../..; \
	done
	@for svc in $(NESTJS_SERVICES); do \
		echo "Building $$svc..."; \
		cd services/$$svc && npm ci && npm run build && cd ../..; \
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
	@for svc in $(NESTJS_SERVICES); do \
		rm -rf services/$$svc/dist; \
	done

dev:
	@echo "Start infra: docker compose up -d"
	@echo "Run Go services:"
	@for svc in $(SERVICES); do \
		echo "  cd services/$$svc && go run ./cmd/server/ &"; \
	done
	@echo "Run NestJS services:"
	@for svc in $(NESTJS_SERVICES); do \
		echo "  cd services/$$svc && npm run start:dev &"; \
	done
