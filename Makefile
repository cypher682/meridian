SHELL := /usr/bin/env bash
MAVEN_IMAGE := maven:3.9-eclipse-temurin-21

.PHONY: demo test smoke build fmt down logs

demo:
	docker compose up --build

test:
	mkdir -p "$$HOME/.m2"
	docker run --rm \
		-u "$$(id -u):$$(id -g)" \
		-e HOME=/tmp \
		-e MAVEN_CONFIG=/tmp/.m2 \
		-e MAVEN_OPTS="-Dmaven.repo.local=/tmp/.m2" \
		-v "$$PWD":/src \
		-v "$$HOME/.m2":/tmp/.m2 \
		-w /src \
		$(MAVEN_IMAGE) mvn -B test

smoke:
	bash scripts/smoke.sh

build:
	mkdir -p "$$HOME/.m2"
	docker run --rm \
		-u "$$(id -u):$$(id -g)" \
		-e HOME=/tmp \
		-e MAVEN_CONFIG=/tmp/.m2 \
		-e MAVEN_OPTS="-Dmaven.repo.local=/tmp/.m2" \
		-v "$$PWD":/src \
		-v "$$HOME/.m2":/tmp/.m2 \
		-w /src \
		$(MAVEN_IMAGE) mvn -B -DskipTests package

fmt:
	mkdir -p "$$HOME/.m2"
	docker run --rm \
		-u "$$(id -u):$$(id -g)" \
		-e HOME=/tmp \
		-e MAVEN_CONFIG=/tmp/.m2 \
		-e MAVEN_OPTS="-Dmaven.repo.local=/tmp/.m2" \
		-v "$$PWD":/src \
		-v "$$HOME/.m2":/tmp/.m2 \
		-w /src \
		$(MAVEN_IMAGE) mvn -B spotless:apply

down:
	docker compose down -v

logs:
	docker compose logs -f
