# qubership-dbaas overrides for the shared consumer Makefile in Makefile.precheck.
# Set values here, then include that file. Do not edit Makefile.precheck — refresh
# it from platform-ci-toolkit/linter/templates/Makefile when the template changes:
#   make sync-precheck-makefile PLATFORM_CI_TOOLKIT_RAW_URL=<raw-url>

LINTER_IMAGE ?= ghcr.io/netcracker/linter
LINTER_TAG ?= common_linter_integration

# Repo default branch is main (image / Makefile.precheck default is master).
DEFAULT_BRANCH ?= main

# Root agent-packages plus skills under dbaas-operator/.
SKILLCHECK_PATTERNS ?= agent-packages/*/.apm/skills dbaas-operator/agent-packages/*/.apm/skills
export SKILLCHECK_PATTERNS

# Go tests only for the operator module (not test-apps).
GO_TEST_MODULES ?= dbaas-operator

PY_BUILD ?= PYTHONPATH=dbaas-operator/agent-packages $(PYTHON) -m pip install -r dbaas-operator/agent-packages/requirements-test.txt

include $(dir $(abspath $(lastword $(MAKEFILE_LIST))))Makefile.precheck
