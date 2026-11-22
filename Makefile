.PHONY: build unit-test integration-test e2e-test test up down clean images publish k8s-up k8s-up-dynatrace k8s-test k8s-down k8s-render-dynatrace

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

up:
	./scripts/compose-up.sh test
	./scripts/wait-ready.sh

down:
	./scripts/compose-down.sh

clean:
	REMOVE_VOLUMES=true ./scripts/compose-down.sh
	rm -rf test/reports/*.json test/reports/*.txt test/reports/*.xml

publish:
	./scripts/publish-images.sh

k8s-up:
	./scripts/k8s-up.sh local-observability

k8s-up-dynatrace:
	./scripts/k8s-up.sh dynatrace-ready

k8s-test:
	./scripts/k8s-test.sh

k8s-down:
	./scripts/k8s-down.sh

k8s-render-dynatrace:
	kubectl kustomize k8s/overlays/dynatrace-ready
