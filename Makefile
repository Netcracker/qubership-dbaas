# Consumer Makefile for qubership-dbaas.
# Based on platform-ci-toolkit/linter/templates/Makefile.consumer.
# This Makefile works in two modes:
#   - Local: it pulls the linter image and runs lint via Docker; tests run on the
#            host.
#   - CI:    it runs /usr/local/bin/run.sh directly because the job already
#            executes inside the linter container; tests run there too.

LINTER_IMAGE ?= ghcr.io/netcracker/linter
LINTER_TAG ?= common_linter_integration
LINTER_IMAGE_FULL := $(LINTER_IMAGE):$(LINTER_TAG)

REPO_ROOT := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
DEFAULT_BRANCH ?= main

# Go module cache override. If set, it is passed to the linter container.
# The common linter image defaults to /tmp/lint/.go/pkg/mod.
GOMODCACHE ?=

# Optional: shell globs relative to the repository root that point to skill
# bundles or individual skills. When set, `make lint` also runs skillcheck.
SKILLCHECK_PATTERNS ?= dbaas-operator/agent-packages/*/.apm/skills
export SKILLCHECK_PATTERNS

# Optional: synchronize this Makefile from the upstream template.
# Set PLATFORM_CI_TOOLKIT_RAW_URL to the raw URL of linter/templates/Makefile.consumer.
sync-linter-makefile:
ifdef PLATFORM_CI_TOOLKIT_RAW_URL
	curl -fsSL -o $(REPO_ROOT)/Makefile.linter $(PLATFORM_CI_TOOLKIT_RAW_URL)
else
	@echo "Set PLATFORM_CI_TOOLKIT_RAW_URL to the raw URL of linter/templates/Makefile.consumer"
	@exit 1
endif

# Forward environment variables used by the common linter. They are only passed
# through if set in the caller's environment.
LINTER_ENV ?= \
	-e RUN_LOCAL=true \
	-e DEFAULT_BRANCH=$(DEFAULT_BRANCH) \
	-e VALIDATE_ALL_CODEBASE=false \
	-e FILTER_REGEX_INCLUDE \
	-e FILTER_REGEX_EXCLUDE \
	-e GOPROXY \
	$(if $(GOMODCACHE),-e GOMODCACHE=$(GOMODCACHE)) \
	-e SKILLCHECK_PATTERNS=$(SKILLCHECK_PATTERNS)

LINTER_VOLUMES ?= \
	-v "$(REPO_ROOT):/tmp/lint"

LINTER_RUN ?= docker run --rm \
	$(LINTER_ENV) \
	$(LINTER_VOLUMES) \
	$(LINTER_IMAGE_FULL)

# Test targets run on the host, or inside the common linter container in CI.
# Override Go module auto-detection; qubership-dbaas keeps its Go code under
# dbaas-operator/.
GO_TEST_MODULES ?= dbaas-operator
# Python interpreter to use for test-python.
PYTHON ?= python3
# Install Python test dependencies before running tests.
PY_BUILD ?= PYTHONPATH=dbaas-operator/agent-packages $(PYTHON) -m pip install -r dbaas-operator/agent-packages/requirements-test.txt
# Default Python test command.
PYTHON_TEST_CMD ?= $(PYTHON) -m pytest

# Java build/test command. Override if your repo uses the Maven wrapper
# (`./mvnw`) or different goals such as `verify`.
JAVA_BUILD ?= mvn -q test
# Auto-detect Maven modules by finding pom.xml files.
JAVA_TEST_MODULES ?= $(shell find $(REPO_ROOT) -name pom.xml -not -path '*/target/*' -exec dirname {} \;)

.PHONY: verify test test-go test-python test-java

verify: lint test

test: test-go test-python test-java

test-go:
	@echo ""; \
	echo "=== Running Go tests ==="; \
	repo_root="$(REPO_ROOT)"; \
	repo_root="$${repo_root%/}"; \
	base_branch="$(DEFAULT_BRANCH)"; \
	base_ref="$$base_branch"; \
	echo "[test-go] REPO_ROOT=$$repo_root"; \
	echo "[test-go] DEFAULT_BRANCH=$$base_branch"; \
	if ! git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		base_ref="origin/$$base_branch"; \
	fi; \
	if git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		changed_go_files=$$(git -C "$$repo_root" diff --name-only "$$base_ref" -- | grep -E '\.(go|mod|sum)$$' || true); \
		echo "[test-go] Changed Go files:"; \
		echo "$$changed_go_files"; \
		if [ -z "$$changed_go_files" ]; then \
			echo "[test-go] No Go, go.mod, or go.sum files changed against $$base_ref; skipping"; \
			exit 0; \
		fi; \
		echo "[test-go] Go, go.mod, or go.sum files changed against $$base_ref"; \
	else \
		echo "[test-go] Default branch $$base_branch (or origin/$$base_branch) not found; running all Go tests"; \
	fi; \
	modules="$(GO_TEST_MODULES)"; \
	if [ -z "$$modules" ]; then \
		echo "[test-go] No Go modules found"; \
		exit 0; \
	fi; \
	echo "[test-go] Detected modules: $$modules"; \
	for mod in $$modules; do \
		rel_mod="$${mod#$$repo_root/}"; \
		if find "$$mod" -name '*_test.go' -print -quit | grep -q .; then \
			echo "=== Testing $$rel_mod ==="; \
			(cd "$$mod" && go test ./...); \
		else \
			echo "[test-go] Skipping $$rel_mod (no Go tests found)"; \
		fi; \
	done

test-python:
	@echo ""; \
	echo "=== Running Python tests ==="; \
	repo_root="$(REPO_ROOT)"; \
	repo_root="$${repo_root%/}"; \
	base_branch="$(DEFAULT_BRANCH)"; \
	base_ref="$$base_branch"; \
	if [ -z "$(PY_BUILD)" ]; then \
		echo "[test-python] PY_BUILD not set; skipping"; \
		exit 0; \
	fi; \
	if ! git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		base_ref="origin/$$base_branch"; \
	fi; \
	if git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		changed_py_files=$$(git -C "$$repo_root" diff --name-only "$$base_ref" -- | grep -E '\.py$$' || true); \
		echo "[test-python] Changed Python files:"; \
		echo "$$changed_py_files"; \
		if [ -z "$$changed_py_files" ]; then \
			echo "[test-python] No Python files changed against $$base_ref; skipping"; \
			exit 0; \
		fi; \
		echo "[test-python] Python files changed against $$base_ref"; \
	else \
		echo "[test-python] Default branch $$base_branch (or origin/$$base_branch) not found; running Python tests"; \
	fi; \
	echo "[test-python] Running PY_BUILD: $(PY_BUILD)"; \
	py_build_cmd="$(PY_BUILD)"; \
	if [ -n "$$py_build_cmd" ]; then \
		eval "$$py_build_cmd"; py_build_rc=$$?; \
	else \
		py_build_rc=0; \
	fi; \
	if [ $$py_build_rc -ne 0 ]; then exit $$py_build_rc; fi; \
	echo "[test-python] Installing pytest"; \
	$(PYTHON) -m pip install --quiet pytest || exit 1; \
	echo "[test-python] Running: $(PYTHON_TEST_CMD)"; \
	$(PYTHON_TEST_CMD); test_rc=$$?; if [ $$test_rc -eq 5 ]; then echo "[test-python] pytest exit code 5 (no tests collected) treated as success"; test_rc=0; fi; exit $$test_rc

test-java:
	@echo ""; \
	echo "=== Running Java tests ==="; \
	repo_root="$(REPO_ROOT)"; \
	repo_root="$${repo_root%/}"; \
	base_branch="$(DEFAULT_BRANCH)"; \
	base_ref="$$base_branch"; \
	if ! git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		base_ref="origin/$$base_branch"; \
	fi; \
	if git -C "$$repo_root" rev-parse --verify "$$base_ref" >/dev/null 2>&1; then \
		changed_java_files=$$(git -C "$$repo_root" diff --name-only "$$base_ref" -- | grep -E '\.(java|xml)$$' || true); \
		echo "[test-java] Changed Java/Maven files:"; \
		echo "$$changed_java_files"; \
		if [ -z "$$changed_java_files" ]; then \
			echo "[test-java] No Java or Maven files changed against $$base_ref; skipping"; \
			exit 0; \
		fi; \
		echo "[test-java] Java or Maven files changed against $$base_ref"; \
	else \
		echo "[test-java] Default branch $$base_branch (or origin/$$base_branch) not found; running all Java tests"; \
	fi; \
	modules="$(JAVA_TEST_MODULES)"; \
	if [ -z "$$modules" ]; then \
		echo "[test-java] No Maven modules found"; \
		exit 0; \
	fi; \
	echo "[test-java] Detected modules: $$modules"; \
	for mod in $$modules; do \
		rel_mod="$${mod#$$repo_root/}"; \
		echo "=== Testing $$rel_mod ==="; \
		(cd "$$mod" && $(JAVA_BUILD)); \
	done

ifeq ($(CI),true)

# CI mode: we are already running inside the linter container.
# The repository is mounted at REPO_ROOT (e.g. /builds/...), not /tmp/lint.
# Link /tmp/lint to the checkout so the Dockerfile's GOMODCACHE setting
# (/tmp/lint/.go/pkg/mod) resolves inside the repository and CI can save it.
.PHONY: prepare-ci-workspace lint lint-super-linter skill-validate lint-fix

prepare-ci-workspace:
	@if [ ! -e /tmp/lint ]; then \
		echo "[prepare-ci-workspace] Linking /tmp/lint -> $(REPO_ROOT)"; \
		ln -s "$(REPO_ROOT)" /tmp/lint; \
	fi

lint: lint-super-linter skill-validate
lint-super-linter: prepare-ci-workspace
skill-validate: prepare-ci-workspace

lint-super-linter:
	@echo ""
	@echo "=== Running Super-Linter ==="
	/usr/local/bin/run.sh super-linter

skill-validate:
	@if [ -z "$(SKILLCHECK_PATTERNS)" ]; then \
		echo "[skill-validate] SKILLCHECK_PATTERNS not set; skipping"; \
		exit 0; \
	fi; \
	echo ""; \
	echo "=== Running skill validation ==="; \
	/usr/local/bin/run.sh skillcheck

lint-fix:
	FIX_GO=true FIX_MARKDOWN=true FIX_YAML_PRETTIER=true /usr/local/bin/run.sh super-linter

# In CI, tests also need /tmp/lint linked before go test uses GOMODCACHE.
test-go: prepare-ci-workspace
test-python: prepare-ci-workspace
test-java: prepare-ci-workspace

else

# Local mode: run the linter container for lint targets; run tests on the host.
.PHONY: ensure-linter-image lint lint-super-linter skill-validate lint-fix

ensure-linter-image:
	@if [ -z "$$(docker images -q $(LINTER_IMAGE_FULL))" ]; then \
		echo "Pulling $(LINTER_IMAGE_FULL)..."; \
		docker pull $(LINTER_IMAGE_FULL); \
	fi

lint: lint-super-linter skill-validate

lint-super-linter: ensure-linter-image
	@echo ""
	@echo "=== Running Super-Linter ==="
	$(LINTER_RUN) super-linter

skill-validate: ensure-linter-image
	@if [ -z "$(SKILLCHECK_PATTERNS)" ]; then \
		echo "[skill-validate] SKILLCHECK_PATTERNS not set; skipping"; \
		exit 0; \
	fi; \
	echo ""; \
	echo "=== Running skill validation ==="; \
	$(LINTER_RUN) skillcheck

lint-fix: ensure-linter-image
	docker run --rm \
		-e FIX_GO=true \
		-e FIX_MARKDOWN=true \
		-e FIX_YAML_PRETTIER=true \
		$(LINTER_ENV) \
		$(LINTER_VOLUMES) \
		$(LINTER_IMAGE_FULL) super-linter

endif
