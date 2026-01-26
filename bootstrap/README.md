# DBaaS Installation Script

This repository contains a comprehensive installation script for deploying Database-as-a-Service (DBaaS) using Helm and Kubernetes. The script manages the installation and uninstallation of Patroni Core, Patroni Services, and DBaaS Aggregator components.

## Quick start
### 1. Prepare config file
Script distributed with 2 prepared configurations:  
   - local.mk - for local deployment
   - aws.mk - for AWS deployment

### 2. Execute installation command

#### Local installation (minikube/rancher desktop/...)
  `make install CONFIG_FILE=local.mk`
  or
  `make install`
  
  local.mk applied by default

#### AWS installation
  `make install CONFIG_FILE=aws.mk`

## Usage
Usage: `make <target> [CONFIG_FILE=local.mk]`

Targets:
  - install          - Install all DBaaS components and run smoke test
  - install-without-test - Install all DBaaS components without smoke test
  - uninstall   - Uninstall all DBaaS components
  - smoke-test  - Test database creation via REST API
  - validate    - Validate configuration and prerequisites
  - show-config - Show current configuration
  - clean       - Clean up repositories
  - help        - Show this help message

Examples:
  - `make install CONFIG_FILE=local.mk`
  - `make uninstall CONFIG_FILE=local.mk`
  - `make validate CONFIG_FILE=local.mk`

## Prerequisites

Before running the installation script, ensure you have the following tools installed and configured:

### Required Tools

1. **Helm**
2. **kubectl**
3. **git**

### Kubernetes Cluster Requirements

- A running Kubernetes cluster. 
- (optional) At least one node should have label "NODE_SELECTOR_DBAAS_KEY=REGION_DBAAS" (Label can be added by srcipt)
- (optional) Existing namespaces. Can be created during installation - requires respective priveleges
- (optional) Required Custom Resource Definitions (CRDs) installed. CRDs can be installed by script - requires respective priveleges

### Nodes
  Set `ADD_DBAAS_NODE_LABEL=true` to label one arbitrary node during installation - requires respective priveleges

### Namespaces

  Specify in configuration 2 namespaces: 
  ```PG_NAMESPACE, DBAAS_NAMESPACE ```
  
  Set ```CREATE_NAMESPACE=true```, if you want to create namespaces during installation (you need to have sufficient cluster priveleges) 
  Set ```CREATE_NAMESPACE=false```, if you have pre-created namespaces

### Required CRDs

- `patronicores.qubership.org`
- `patroniservices.qubership.org`


Set ```SKIP_CRDS=false``` if you want to install CRDs along with helm packages (you need to have sufficient cluster priveleges) 
  
Set ```SKIP_CRDS=true``` to skip CRDs installation. The script will check CRDs presence during prerequisites validation and provide installation instructions if they're missing

### Required permissions

Minimum permissions for installation (without admin priveleges to create namespaces and CRDs)

- Ensure namespace admin priveleges for PG_NAMESPACE, DBAAS_NAMESPACE
- Ensure to have role

```
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: qubership
rules:
- apiGroups:
  - "netcracker.com"
  resources:
  - "*"
  verbs:
  - get
  - create
  - get
  - list
  - patch
  - update
  - watch 
  - delete
``` 

## Configuration

### Configuration File

The script uses a .mk configuration file to define all installation and helm packages parameters

### Parameter Reference Table

| Parameter Name | Example Value | Description |
|---|---|---|
| **Configuration Files** |
| `PATRONI_CORE_VALUES_FILE` | `./patroni-core-values-local.yaml` | Path to Patroni Core Helm values file |
| `DBAAS_VALUES_FILE` | `./dbaas-values.yaml` | Path to DBaaS Aggregator Helm values file |
| `PATRONI_SERVICES_VALUES_FILE` | `./patroni-services-values.yaml` | Path to Patroni Services Helm values file |
| **Namespace Configuration** |
| `PG_NAMESPACE` | `postgres` | Kubernetes namespace for Patroni components |
| `DBAAS_NAMESPACE` | `dbaas` | Kubernetes namespace for DBaaS Aggregator |
| **PostgreSQL Configuration** |
| `POSTGRES_PASSWORD` | `password` | Password for PostgreSQL database |
| `STORAGE_CLASS` | `standard` / `local-path` / `gp2` | Kubernetes storage class for persistent volumes. standard - is value for minikube deployment. local-path - is value for rancher-desktop.  gp2 - is value for AWS. If value is not provided (e.g. in local.mk) - script will try to determine default Storage Class in cluster and apply it. (Requires priveleges to list Storage Classes) |
| `PATRONI_REPLICAS_NUMBER` | 1 | Number of patroni nodes |
| **DBaaS Configuration** |
| `DBAAS_SERVICE_NAME` | `dbaas-aggregator` | Service name for DBaaS |
| `NODE_SELECTOR_DBAAS_KEY` | `region` | Node selector label key for DBaaS pod placement. Default DBaaS label `region: database` used in local.mk. If user have no permissions to add custom node labels - try to pick one of exisitng labels |
| `REGION_DBAAS` | `database` | Node selector label value for DBaaS pod placement |
| `TAG` | `dbaas-validation-image-merge-20250617131852-28` | Docker image tag for validation image |
| **Installation Options** |
| `CREATE_NAMESPACE` | `true` | Automatically create namespaces if they don't exist |
| `SKIP_CRDS` | `true`/`false` | Skip Custom Resource Definition installation |
| `ADD_DBAAS_NODE_LABEL` | `true`/`false` | Add 'NODE_SELECTOR_DBAAS_KEY=REGION_DBAAS' label to one arbitrary cluster node for DBaaS requirements satisfaction |

### Values Files

The script uses three main values files templates: 

1. **patroni-core-values.yaml** - Configuration for Patroni Core component
2. **dbaas-values.yaml** - Configuration for DBaaS Aggregator component  
3. **patroni-services-values.yaml** - Configuration for Patroni Services component

The script uses `envsubst` to substitute environment variables in the values files templates

## Installation Process

The installation script performs the following stages:

### Stage 1: Repository Setup
- Clones required repositories:
  - `pgskipper-operator` from GitHub
  - `qubership-dbaas` from GitHub
This is required as there is no public repo for helm packages downloading

### Stage 2: Patroni Core Installation
- Installs Patroni Core using Helm
- Uses the `PATRONI_CORE_VALUES_FILE` configuration

### Stage 3: Wait for Patroni Pods
- Waits for Patroni pods to be ready (timeout: 10 minutes)
- Ensures 2 Patroni pods are running
- Ensures 1 master pod is ready

### Stage 4: DBaaS Aggregator Installation
- Installs DBaaS Aggregator using Helm
- Uses the `DBAAS_VALUES_FILE` configuration

### Stage 5: Wait for DBaaS Aggregator Service
- Waits for DBaaS Aggregator deployment to be ready (timeout: 5 minutes)
- Ensures all replicas are running

### Stage 6: Patroni Services Installation
- Installs Patroni Services using Helm
- Uses the `PATRONI_SERVICES_VALUES_FILE` configuration
- Skips CRD installation (assumes CRDs are pre-installed)

### Stage 7: Wait for Patroni Services Registration
- Waits for Patroni services registration to complete (timeout: 5 minutes)
- Monitors logs for "Registration finished" message

## Uninstallation Process

The uninstallation script performs the following stages in reverse order:

### Stage 1: Uninstall Patroni Services
- Removes Patroni Services Helm release

### Stage 2: Uninstall DBaaS Aggregator
- Removes DBaaS Aggregator Helm release
- Cleans up related secrets:
  - `${DBAAS_SERVICE_NAME}-encryption-secret`
  - `${DBAAS_SERVICE_NAME}-env-variables`
  - `dbaas-storage-credentials`

### Stage 3: Uninstall Patroni Core
- Removes Patroni Core Helm release

### Stage 4: Clean up 

Removes artifacts

- patrony resources
  (Patroni creates programmatically config maps, pvc's, services that are not cleaned by helm)
- patroni CRDs (if `SKIP_CRDS = false`)
- DbaaS node label (if `ADD_DBAAS_NODE_LABEL = true`)
- namespaces (if `CREATE_NAMESPACE = true`)
