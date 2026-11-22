.PHONY: build unit-test integration-test e2e-test test up up-dev up-prod up-prod-otel down clean images publish \
        k8s-up k8s-up-dev k8s-up-prod k8s-up-prod-otel k8s-test k8s-down k8s-render-prod k8s-render-prod-otel

build:
	./scripts/container-build.sh

images:
	./scripts/container-build.sh

unit-test:
	./scripts/container-test.sh unit

integration-test:
	./scripts/container-test.sh integration

e2e-test:
	./scripts/container-test.sh e2e

test:
	./scripts/container-test.sh all

up: up-dev

up-dev:
	./scripts/compose-up.sh dev
	./scripts/wait-ready.sh

up-prod:
	./scripts/compose-up.sh prod
	./scripts/wait-ready.sh

up-prod-otel:
	./scripts/compose-up.sh prod-otel
	./scripts/wait-ready.sh

down:
	./scripts/compose-down.sh

clean:
	REMOVE_VOLUMES=true ./scripts/compose-down.sh
	rm -rf test/reports/*.json test/reports/*.txt test/reports/*.xml

publish:
	./scripts/publish-images.sh

k8s-up: k8s-up-dev

k8s-up-dev:
	./scripts/k8s-up.sh dev

k8s-up-prod:
	./scripts/k8s-up.sh prod

k8s-up-prod-otel:
	./scripts/k8s-up.sh prod-otel

k8s-test:
	./scripts/k8s-test.sh

k8s-down:
	./scripts/k8s-down.sh

k8s-render-prod:
	kubectl kustomize k8s/overlays/prod

k8s-render-prod-otel:
	kubectl kustomize k8s/overlays/prod-otel
