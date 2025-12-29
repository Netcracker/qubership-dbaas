# values files for helm packages
PATRONI_CORE_VALUES_FILE ?= ./patroni-core-values-local.yaml
DBAAS_VALUES_FILE ?= ./dbaas-values.yaml
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
# Validation image tag
TAG ?= latest

# Export all variables for use in shell commands
export PG_NAMESPACE
export DBAAS_NAMESPACE
export DBAAS_SERVICE_NAME
export POSTGRES_PASSWORD
# export STORAGE_CLASS
export PATRONI_REPLICAS_NUMBER
export NODE_SELECTOR_DBAAS_KEY
export REGION_DBAAS
export TAG
export PATRONI_CORE_VALUES_FILE
export DBAAS_VALUES_FILE
export PATRONI_SERVICES_VALUES_FILE 


# installation parameters - not propagated to helm values
CREATE_NAMESPACE ?= true
SKIP_CRDS ?= false
ADD_DBAAS_NODE_LABEL ?= true
