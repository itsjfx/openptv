# Backend Makefile — barebones targets for Phase 01.

GO ?= go
BIN ?= openptvd

.PHONY: run test vet build fmt tidy

run:
	$(GO) run ./cmd/openptvd

test:
	$(GO) test -race ./...

vet:
	$(GO) vet ./...

build:
	$(GO) build -o $(BIN) ./cmd/openptvd

fmt:
	$(GO) fmt ./...

tidy:
	$(GO) mod tidy
