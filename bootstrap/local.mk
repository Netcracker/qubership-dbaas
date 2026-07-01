# values files for helm packages
PATRONI_CORE_VALUES_FILE ?= ./patroni-core-values-local.yaml
DBAAS_VALUES_FILE ?= ./dbaas-values.yaml
DBAAS_OPERATOR_VALUES_FILE ?= ./dbaas-operator-values.yaml
DBAAS_OPERATOR_RESOURCE_PROFILE ?= $(REPOS_DIR)/qubership-dbaas/dbaas-operator/helm-templates/dbaas-operator/resource-profiles/dev.yaml
PATRONI_SERVICES_VALUES_FILE ?= ./patroni-services-values.yaml

# namespace parameters
PG_NAMESPACE ?= postgres
DBAAS_NAMESPACE ?= dbaas

# postgres parameters
POSTGRES_PASSWORD ?= password
#STORAGE_CLASS ?= standard
PATRONI_REPLICAS_NUMBER ?= 1

# dbaas parameters
# DBAAS_SERVICE_NAME is hardcoded in prepare-database.sh, no sense to use another value here
DBAAS_SERVICE_NAME ?= dbaas-aggregator
NODE_SELECTOR_DBAAS_KEY ?= region
REGION_DBAAS ?= database
# Default to the production default (false): the aggregator accepts only HTTP Basic
# Auth and the operator authenticates as the dbaas-operator basic user. Set to true
# to exercise the M2M (projected SA token) path instead. This is the aggregator's
# flag; the operator's defaults to it via OPERATOR_M2M_ENABLED below.
KUBERNETES_M2M_ENABLED ?= false
# Operator's auth mode. Defaults to the aggregator's value so the two match unless
# overridden. Override independently to test the supported hybrid where the operator
# uses Basic Auth against an M2M-enabled aggregator:
#   make ... KUBERNETES_M2M_ENABLED=true OPERATOR_M2M_ENABLED=false
OPERATOR_M2M_ENABLED ?= $(KUBERNETES_M2M_ENABLED)
# Validation image tag
TAG ?= latest
DBAAS_OPERATOR_TAG ?= latest

# Export all variables for use in shell commands
export PG_NAMESPACE
export DBAAS_NAMESPACE
export DBAAS_SERVICE_NAME
export POSTGRES_PASSWORD
# export STORAGE_CLASS
export PATRONI_REPLICAS_NUMBER
export NODE_SELECTOR_DBAAS_KEY
export REGION_DBAAS
export KUBERNETES_M2M_ENABLED
export OPERATOR_M2M_ENABLED
export TAG
export DBAAS_OPERATOR_TAG
export PATRONI_CORE_VALUES_FILE
export DBAAS_VALUES_FILE
export DBAAS_OPERATOR_VALUES_FILE
export DBAAS_OPERATOR_RESOURCE_PROFILE
export PATRONI_SERVICES_VALUES_FILE


# installation parameters - not propagated to helm values
CREATE_NAMESPACE ?= true
SKIP_CRDS ?= false
ADD_DBAAS_NODE_LABEL ?= true
DBAAS_OPERATOR_ENABLED ?= true
export DBAAS_OPERATOR_ENABLED
