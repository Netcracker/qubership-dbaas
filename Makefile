# qubership-dbaas overrides for the shared consumer Makefile in Makefile.linter.
# Set values here, then include that file. Makefile.linter sets REPO_ROOT from its
# own path, so this file restores REPO_ROOT to this checkout afterward.
#
# Refresh Makefile.linter from platform-ci-toolkit when the template changes:
#   make sync-linter-makefile PLATFORM_CI_TOOLKIT_RAW_URL=<raw-url>

REPO_ROOT := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

LINTER_IMAGE ?= ghcr.io/netcracker/linter
LINTER_TAG ?= common_linter_integration

DEFAULT_BRANCH ?= main

# Root agent-packages plus skills under dbaas-operator/.
SKILLCHECK_PATTERNS ?= agent-packages/*/.apm/skills dbaas-operator/agent-packages/*/.apm/skills
export SKILLCHECK_PATTERNS

# Go tests only for the operator module (not test-apps).
GO_TEST_MODULES ?= dbaas-operator

PYTHON ?= python3
PY_BUILD ?= PYTHONPATH=dbaas-operator/agent-packages $(PYTHON) -m pip install -r dbaas-operator/agent-packages/requirements-test.txt

# Evaluate against this checkout before Makefile.linter overwrites REPO_ROOT.
JAVA_TEST_MODULES ?= $(shell find $(REPO_ROOT) -name pom.xml -not -path '*/target/*' -exec dirname {} \;)

include $(REPO_ROOT)Makefile.linter

# Point REPO_ROOT back at this repository (firstword is this Makefile).
REPO_ROOT := $(dir $(abspath $(firstword $(MAKEFILE_LIST))))
