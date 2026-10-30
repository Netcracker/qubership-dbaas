# Consumer Makefile template for platform-ci-toolkit / Super-Linter.
# Copy this file into a consumer repository as Makefile, or include it from a
# consumer Makefile and adjust the variables below if needed.
#
# This Makefile works in two modes:
#   - Local: it pulls the linter image and runs lint via Docker; tests run on
#            the host.
#   - CI:    it runs /usr/local/bin/run.sh directly because the job already
#            executes inside the linter container; tests run there too.

LINTER_IMAGE ?= ghcr.io/netcracker/linter
LINTER_TAG ?= latest
LINTER_IMAGE_FULL := $(LINTER_IMAGE):$(LINTER_TAG)

# When this file is included, firstword is the consumer Makefile, so REPO_ROOT
# resolves to the consumer repository root. When copied as the main Makefile,
# firstword is this file, which also lives at the repository root.
REPO_ROOT ?= $(dir $(abspath $(firstword $(MAKEFILE_LIST))))
DEFAULT_BRANCH ?= master
# Export so CI recipes (`run.sh`) and child processes see the value. Consumers
# override with DEFAULT_BRANCH ?= <branch> in their thin Makefile before include.
export DEFAULT_BRANCH

# Go module cache override. If set, it is passed to the linter container.
# The common linter image defaults to /tmp/lint/.go/pkg/mod.
GOMODCACHE ?=

# Optional: shell globs relative to the repository root that point to skill
# bundles or individual skills. When set, `make lint` also runs skillcheck.
# Example: SKILLCHECK_PATTERNS ?= ".opencode/skills opencode-agents"
SKILLCHECK_PATTERNS ?= agent-packages/*/.apm/skills
export SKILLCHECK_PATTERNS

# Optional: synchronize this Makefile from the upstream template.
# Set PLATFORM_CI_TOOLKIT_RAW_URL to the raw URL of linter/templates/Makefile.
sync-precheck-makefile:
ifdef PLATFORM_CI_TOOLKIT_RAW_URL
	curl -fsSL -o $(REPO_ROOT)/Makefile.precheck $(PLATFORM_CI_TOOLKIT_RAW_URL)
else
	@echo "Set PLATFORM_CI_TOOLKIT_RAW_URL to the raw URL of linter/templates/Makefile"
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
	-e SKILLCHECK_PATTERNS="$(SKILLCHECK_PATTERNS)"

# FILTER_REGEX_INCLUDE can be set to limit Super-Linter to a subset of files,
# for example: make lint FILTER_REGEX_INCLUDE=services/backuper-service/.*

LINTER_VOLUMES ?= \
	-v "$(REPO_ROOT):/tmp/lint"

LINTER_RUN ?= docker run --rm \
	$(LINTER_ENV) \
	$(LINTER_VOLUMES) \
	$(LINTER_IMAGE_FULL)

# Test targets run on the host, or inside the common linter container in CI.
# GO_TEST_MODULES is auto-detected from go.mod files. Set it explicitly to
# override. When a default branch is available and no .go/.mod/.sum files
# changed, Go tests are skipped. Otherwise go test ./... runs in every detected
# module.
GO_TEST_MODULES ?= $(shell find $(REPO_ROOT) -name go.mod -not -path '*/vendor/*' -exec dirname {} \;)
# Python interpreter to use for test-python. Override if only `python` is
# available on the host or in the CI container.
PYTHON ?= python3
# Build step for Python tests. Required for test-python to run. It must install
# test dependencies and can set environment variables such as PYTHONPATH.
# If empty, no py tests run.
PY_BUILD ?=
# Default Python test command. Override to use a different runner or arguments.
PYTHON_TEST_CMD ?= $(PYTHON) -m pytest

# Java build/test command. Override if your repo uses the Maven wrapper
# (`./mvnw`) or different goals such as `verify`.
JAVA_BUILD ?= mvn -q test
# Auto-detect Maven modules by finding pom.xml files. Override with a list of
# directories if auto-detection is too broad.
JAVA_TEST_MODULES ?= $(shell find $(REPO_ROOT) -name pom.xml -not -path '*/target/*' -exec dirname {} \;)

.PHONY: verify test test-go test-python test-java

verify: lint test

test: test-go test-python test-java

# Shared shell setup for test-go / test-python / test-java.
# Sets repo_root and defines check_changed_files LABEL EXT_REGEX KIND_LABEL.
# That helper returns 0 when tests should run and 1 when the target should skip.
define test-shell-helpers
repo_root="$(REPO_ROOT)"; \
repo_root="$${repo_root%/}"; \
check_changed_files() { \
	_label="$$1"; \
	_pattern="$$2"; \
	_kind="$$3"; \
	_base_branch="$(DEFAULT_BRANCH)"; \
	_base_ref="$$_base_branch"; \
	if ! git -C "$$repo_root" rev-parse --verify "$$_base_ref" >/dev/null 2>&1; then \
		_base_ref="origin/$$_base_branch"; \
	fi; \
	if git -C "$$repo_root" rev-parse --verify "$$_base_ref" >/dev/null 2>&1; then \
		if ! git -C "$$repo_root" diff --name-only "$$_base_ref" -- | grep -Eq "$$_pattern"; then \
			echo "[$$_label] No $$_kind files changed against $$_base_ref; skipping"; \
			return 1; \
		fi; \
		echo "[$$_label] $$_kind files changed against $$_base_ref"; \
	else \
		echo "[$$_label] Default branch $$_base_branch (or origin/$$_base_branch) not found; running all $$_kind tests"; \
	fi; \
	return 0; \
}
endef

test-go:
	@$(test-shell-helpers); \
	echo ""; \
	echo "=== Running Go tests ==="; \
	echo "[test-go] REPO_ROOT=$$repo_root"; \
	echo "[test-go] DEFAULT_BRANCH=$(DEFAULT_BRANCH)"; \
	check_changed_files test-go '\.(go|mod|sum)$$' 'Go, go.mod, or go.sum' || exit 0; \
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
	@$(test-shell-helpers); \
	echo ""; \
	echo "=== Running Python tests ==="; \
	if [ -z "$(PY_BUILD)" ]; then \
		echo "[test-python] PY_BUILD not set; skipping"; \
		exit 0; \
	fi; \
	check_changed_files test-python '\.py$$' 'Python' || exit 0; \
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
	@$(test-shell-helpers); \
	echo ""; \
	echo "=== Running Java tests ==="; \
	check_changed_files test-java '\.(java|xml)$$' 'Java or Maven' || exit 0; \
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
