.PHONY: build unit-test integration-test e2e-test test up down clean images publish

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
	./scripts/compose-down.sh
	rm -rf test/reports/*.json test/reports/*.txt test/reports/*.xml

publish:
	./scripts/publish-images.sh
