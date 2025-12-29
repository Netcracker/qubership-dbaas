# values files for helm packages
PATRONI_CORE_VALUES_FILE ?= ./patroni-core-values.yaml
DBAAS_VALUES_FILE ?= ./dbaas-values.yaml
PATRONI_SERVICES_VALUES_FILE ?= ./patroni-services-values.yaml

# namespace parameters
PG_NAMESPACE ?= core-1-postgres
DBAAS_NAMESPACE ?= core-1-dbaas

# postgres parameters
POSTGRES_PASSWORD ?= password
STORAGE_CLASS ?= gp2
PATRONI_REPLICAS_NUMBER ?= 1

# dbaas parameters
# DBAAS_SERVICE_NAME is hardcoded in prepare-database.sh, no sense to use another value here
DBAAS_SERVICE_NAME ?= dbaas-aggregator
NODE_SELECTOR_DBAAS_KEY ?= topology.k8s.aws/zone-id
REGION_DBAAS ?= use1-az2
# Validation image tag
TAG ?= dbaas-validation-image-merge-20250617131852-28

# Export all variables for use in shell commands
export PG_NAMESPACE
export DBAAS_NAMESPACE
export DBAAS_SERVICE_NAME
export POSTGRES_PASSWORD
export STORAGE_CLASS
export PATRONI_REPLICAS_NUMBER
export NODE_SELECTOR_DBAAS_KEY
export REGION_DBAAS
export TAG
export PATRONI_CORE_VALUES_FILE
export DBAAS_VALUES_FILE
export PATRONI_SERVICES_VALUES_FILE 

# installation parameters - not propagated to helm values
CREATE_NAMESPACE ?= false
SKIP_CRDS ?= true
ADD_DBAAS_NODE_LABEL ?= false