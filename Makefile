# qubership-dbaas overrides for the shared consumer Makefile in Makefile.common.
# Set values here, then include that file. Do not edit Makefile.common — refresh
# it from platform-ci-toolkit/linter/templates/Makefile.common.mk when the template changes:
#   make sync-precheck-makefile PLATFORM_CI_TOOLKIT_RAW_URL=<raw-url>

LINTER_IMAGE ?= ghcr.io/netcracker/linter
LINTER_TAG ?= common_linter_integration

# Repo default branch is main (image / Makefile.common default is master).
DEFAULT_BRANCH ?= main

# Root agent-packages plus skills under dbaas-operator/.
SKILLCHECK_PATTERNS ?= agent-packages/*/.apm/skills dbaas-operator/agent-packages/*/.apm/skills
export SKILLCHECK_PATTERNS

# Go tests only for the operator module (not test-apps).
GO_TEST_MODULES ?= dbaas-operator

PY_BUILD ?= PYTHONPATH=dbaas-operator/agent-packages $(PYTHON) -m pip install -r dbaas-operator/agent-packages/requirements-test.txt

include Makefile.common
