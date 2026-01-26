# DBaaS Installation Makefile

# Include configuration
CONFIG_FILE ?= local.mk
include $(CONFIG_FILE)

# Repository directory
REPOS_DIR ?= ./.tmp

# Define colors for output
CYAN := \033[0;36m
GREEN := \033[0;32m
BLUE := \033[0;34m
RED := \033[0;31m
NC := \033[0m

# Conditional helm options
CREATE_NAMESPACE_OPTION := $(if $(filter true,$(CREATE_NAMESPACE)),--create-namespace,)
SKIP_CRDS_OPTION := $(if $(filter true,$(SKIP_CRDS)),--skip-crds,)

# Default target
.PHONY: help
help:
	@echo "$(BLUE)=== DBaaS Installation Makefile ===$(NC)"
	@echo ""
	@echo "Usage: make <target> [CONFIG_FILE=local.mk]"
	@echo ""
	@echo "Targets:"
	@echo "  install          - Install all DBaaS components and run smoke test"
	@echo "  install-without-test - Install all DBaaS components without smoke test"
	@echo "  uninstall        - Uninstall all DBaaS components"
	@echo "  smoke-test       - Test database creation via REST API"
	@echo "  validate         - Validate configuration and prerequisites"
	@echo "  show-config      - Show current configuration"
	@echo "  clean            - Clean up repositories"
	@echo "  help             - Show this help message"
	@echo ""
	@echo "Examples:"
	@echo "  make install CONFIG_FILE=local.mk"
	@echo "  make uninstall CONFIG_FILE=local.mk"
	@echo "  make validate CONFIG_FILE=local.mk"

# Show configuration
.PHONY: show-config
show-config:
	@echo "$(BLUE)=== ENVIRONMENT VARIABLES ===$(NC)"
	@echo "$(CYAN)--- Postgres namespace: $(NC)$(PG_NAMESPACE)"
	@echo "$(CYAN)--- DBaaS namespace: $(NC)$(DBAAS_NAMESPACE)"
	@echo "$(CYAN)--- DBaaS service name: $(NC)$(DBAAS_SERVICE_NAME)"
	@echo "$(CYAN)--- Postgres password: $(NC)$(POSTGRES_PASSWORD)"
	@echo "$(CYAN)--- Storage class: $(NC)$(STORAGE_CLASS)"
	@echo "$(CYAN)--- Patroni replicas number: $(NC)$(PATRONI_REPLICAS_NUMBER)"
	@echo "$(CYAN)--- Node selector key: $(NC)$(NODE_SELECTOR_DBAAS_KEY)"
	@echo "$(CYAN)--- Region: $(NC)$(REGION_DBAAS)"
	@echo "$(CYAN)--- Image Tag: $(NC)$(TAG)"
	@echo "$(CYAN)--- Patroni Core Values File: $(NC)$(PATRONI_CORE_VALUES_FILE)"
	@echo "$(CYAN)--- DBaaS Values File: $(NC)$(DBAAS_VALUES_FILE)"
	@echo "$(CYAN)--- Patroni Services Values File: $(NC)$(PATRONI_SERVICES_VALUES_FILE)"
	@echo ""

# Validate prerequisites
.PHONY: validate
validate:
	@echo "$(BLUE)=== PREREQUISITE CHECKS ===$(NC)"
	@echo "$(CYAN)--- Checking if helm is available...$(NC)"
	@command -v helm >/dev/null 2>&1 || (echo "$(RED)Error: helm is not installed or not in PATH$(NC)" && echo "$(CYAN)Please install helm: https://helm.sh/docs/intro/install/$(NC)" && exit 1)
	@echo "$(GREEN)--- helm found: $(shell helm version --short)$(NC)"
	@echo "$(CYAN)--- Checking if git is available...$(NC)"
	@command -v git >/dev/null 2>&1 || (echo "$(RED)Error: git is not installed or not in PATH$(NC)" && echo "$(CYAN)Please install git: https://git-scm.com/downloads$(NC)" && exit 1)
	@echo "$(GREEN)--- git found: $(shell git --version)$(NC)"
	@echo "$(CYAN)--- Checking if kubectl is available...$(NC)"
	@command -v kubectl >/dev/null 2>&1 || (echo "$(RED)Error: kubectl is not installed or not in PATH$(NC)" && echo "$(CYAN)Please install kubectl: https://kubernetes.io/docs/tasks/tools/$(NC)" && exit 1)
	@echo "$(GREEN)--- kubectl found: $(shell kubectl version --client)$(NC)"
	@echo "$(CYAN)--- Checking kubectl cluster connection...$(NC)"
	@kubectl cluster-info >/dev/null 2>&1 || (echo "$(RED)Error: kubectl cannot connect to cluster$(NC)" && echo "$(CYAN)Please check your kubeconfig and cluster status$(NC)" && exit 1)
	@echo "$(GREEN)--- kubectl cluster connection successful$(NC)"
	@echo ""
	@echo "$(BLUE)=== PREREQUISITE CHECK ===$(NC)"
ifeq ($(SKIP_CRD),true)
	@echo "$(CYAN)--- Checking if CRDs qubership.org.PatroniCore, qubership.org.PatroniServices exists...$(NC)"
	@kubectl api-resources | grep -q patronicores || (echo "$(RED)Error: CRD patronicores.qubership.org not found$(NC)" && echo "$(CYAN)Please install the CRD first:$(NC)" && echo "$(CYAN)kubectl apply -f ./pgskipper-operator/charts/patroni-core/crds/qubership.org_patronicores.yaml$(NC)" && exit 1)
	@echo "$(GREEN)--- CRD patronicores.qubership.org found$(NC)"
	@kubectl api-resources | grep -q patroniservices || (echo "$(RED)Error: CRD patroniservices.qubership.org not found$(NC)" && echo "$(CYAN)Please install the CRD first:$(NC)" && echo "$(CYAN)kubectl apply -f ./pgskipper-operator/operator/charts/patroni-services/crds/qubership.org_patroniservices.yaml$(NC)" && exit 1)
	@echo "$(GREEN)--- CRD patroniservices.qubership.org found$(NC)"
else
	@echo "$(CYAN)--- Skipping CRD validation (CRD will be installed)...$(NC)"
endif
	@echo ""
	@echo "$(BLUE)=== NAMESPACE CHECK ===$(NC)"
ifneq ($(CREATE_NAMESPACE),true)
	@echo "$(CYAN)--- Checking if required namespaces exist...$(NC)"
	@kubectl get namespace $(PG_NAMESPACE) >/dev/null 2>&1 || (echo "$(RED)Error: Namespace $(PG_NAMESPACE) not found$(NC)" && echo "$(CYAN)Please create the namespace first:$(NC)" && echo "$(CYAN)kubectl create namespace $(PG_NAMESPACE)$(NC)" && exit 1)
	@echo "$(GREEN)--- Namespace $(PG_NAMESPACE) found$(NC)"
	@kubectl get namespace $(DBAAS_NAMESPACE) >/dev/null 2>&1 || (echo "$(RED)Error: Namespace $(DBAAS_NAMESPACE) not found$(NC)" && echo "$(CYAN)Please create the namespace first:$(NC)" && echo "$(CYAN)kubectl create namespace $(DBAAS_NAMESPACE)$(NC)" && exit 1)
	@echo "$(GREEN)--- Namespace $(DBAAS_NAMESPACE) found$(NC)"
else
	@echo "$(CYAN)--- Skipping namespace check (CREATE_NAMESPACE=true)...$(NC)"
endif
	@echo ""
	@echo "$(BLUE)=== NODE with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS) CHECK ===$(NC)"
ifeq ($(ADD_DBAAS_NODE_LABEL),true)
	@echo "$(CYAN)--- Checking if there is at least 1 node with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)' (for proper DBaaS installation)...$(NC)"
	@NUMBER_OF_LABELED_NODES=$$(kubectl get nodes -l $(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS) --no-headers 2>/dev/null | wc -l); \
	echo "$(GREEN)--- Found $$NUMBER_OF_LABELED_NODES node(s)$(NC)"; \
	if [ "$$NUMBER_OF_LABELED_NODES" -ge 1 ]; then \
		echo "$(GREEN)--- Condition satisfied$(NC)"; \
	else \
		echo "$(RED)--- Condition not satisfied$(NC)"; \
		echo "$(CYAN)--- Adding label according to flag ADD_DBAAS_NODE_LABEL$(NC)"; \
		NODE=$$(kubectl get nodes -o custom-columns=NAME:.metadata.name,UNSCHEDULABLE:.spec.unschedulable,TAINTS:.spec.taints --no-headers \
  			| awk '!/true/ && !/NoSchedule|NoExecute/ { print $$1 }' | tail -n 1); \
		if [ -z "$$NODE" ]; then \
			echo "$(RED)--- Error: No suitable node found for labeling$(NC)"; \
			exit 1; \
		fi; \
		echo "$(CYAN)--- Candidate node: $$NODE$(NC)"; \
		kubectl label node $$NODE $(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS) --overwrite >/dev/null; \
		echo "$(GREEN)--- Labeled $$NODE with '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)'$(NC)"; \
	fi
else
	@echo "$(CYAN)--- Skipping nodes check (ADD_DBAAS_NODE_LABEL=false)...$(NC)"
	@echo "$(CYAN)--- Please ensure your cluster has at least 1 node with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)'$(NC)"
endif
	@echo ""	

# Setup repositories
.PHONY: setup-repos
setup-repos:
	@echo "$(BLUE)=== STAGE 1: Repository Setup ===$(NC)"
	@echo "$(CYAN)--- Stage 1 BEGIN: Cloning repositories...$(NC)"
	@mkdir -p $(REPOS_DIR)
	@git clone --depth 1 https://github.com/Netcracker/pgskipper-operator.git $(REPOS_DIR)/pgskipper-operator 2>/dev/null || echo "Directory already exists"
	@git clone --depth 1 https://github.com/Netcracker/qubership-dbaas.git $(REPOS_DIR)/qubership-dbaas 2>/dev/null || echo "Directory already exists"
	@echo "$(GREEN)--- Stage 1 END: Repositories ready$(NC)"
	@echo ""

# Install Patroni Core
.PHONY: install-patroni-core
install-patroni-core:
	@echo "$(BLUE)=== STAGE 2: Patroni Core Installation ===$(NC)"
	@echo "$(CYAN)--- Stage 2 BEGIN: Installing Patroni Core...$(NC)"
	@echo "$(BLUE)=== AUTO-DETECTING STORAGE CLASS ===$(NC)"
	@if [ -z "$(STORAGE_CLASS)" ]; then \
		echo "$(CYAN)--- STORAGE_CLASS not specified, detecting default storage class...$(NC)"; \
		DEFAULT_STORAGE_CLASS=$$(kubectl get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null); \
		if [ -z "$$DEFAULT_STORAGE_CLASS" ]; then \
			echo "$(CYAN)--- No default storage class found, checking for any available storage class...$(NC)"; \
			DEFAULT_STORAGE_CLASS=$$(kubectl get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
		fi; \
		if [ -z "$$DEFAULT_STORAGE_CLASS" ]; then \
			echo "$(RED)Error: No storage class found in cluster$(NC)"; \
			echo "$(CYAN)Please create a storage class or specify STORAGE_CLASS manually$(NC)"; \
			exit 1; \
		fi; \
		echo "$(GREEN)--- Auto-detected storage class: $$DEFAULT_STORAGE_CLASS$(NC)"; \
		echo "$(CYAN)--- Setting STORAGE_CLASS=$$DEFAULT_STORAGE_CLASS$(NC)"; \
		export STORAGE_CLASS=$$DEFAULT_STORAGE_CLASS; \
		echo "$(GREEN)--- Exported STORAGE_CLASS=$$STORAGE_CLASS$(NC)"; \
	else \
		echo "$(GREEN)--- STORAGE_CLASS already specified: $(STORAGE_CLASS)$(NC)"; \
	fi; \
	echo "$(GREEN)--- Final STORAGE_CLASS value: $$STORAGE_CLASS$(NC)"; \
	echo ""; \
	envsubst < $(PATRONI_CORE_VALUES_FILE) | helm upgrade --install --namespace=$(PG_NAMESPACE) $(CREATE_NAMESPACE_OPTION) $(SKIP_CRDS_OPTION) \
		patroni-core $(REPOS_DIR)/pgskipper-operator/operator/charts/patroni-core -f - || \
		(echo "$(RED)Error: Patroni Core installation failed$(NC)" && echo "$(CYAN)Please check the helm output above for details$(NC)" && exit 1)
	@echo "$(GREEN)--- Stage 2 END: Patroni Core installation completed$(NC)"
	@echo ""

# Wait for Patroni pods
.PHONY: wait-patroni-pods
wait-patroni-pods:
	@echo "$(BLUE)=== STAGE 3: Wait for Patroni Pods ===$(NC)"
	@echo "$(CYAN)--- Stage 3 BEGIN: Waiting for patroni pods to be ready (timeout: 10 minutes)...$(NC)"
	@TIMEOUT=600; START_TIME=$$(date +%s); \
	while true; do \
		CURRENT_TIME=$$(date +%s); \
		ELAPSED_TIME=$$((CURRENT_TIME - START_TIME)); \
		if [ $$ELAPSED_TIME -ge $$TIMEOUT ]; then \
			echo "$(RED)Error: Timeout reached after 10 minutes. Not all pods are running.$(NC)"; \
			exit 1; \
		fi; \
		RUNNING_PATRONI_PODS=$$(kubectl -n $(PG_NAMESPACE) get pods --selector=app=patroni --field-selector=status.phase=Running -o name | wc -l); \
		RUNNING_MASTER_PODS=$$(kubectl -n $(PG_NAMESPACE) get pods --selector=pgtype=master --field-selector=status.phase=Running -o name | wc -l); \
		if [ "$$RUNNING_PATRONI_PODS" -eq $(PATRONI_REPLICAS_NUMBER) ] && [ "$$RUNNING_MASTER_PODS" -eq 1 ]; then \
			echo "All patroni pods are running ($$RUNNING_PATRONI_PODS/$(PATRONI_REPLICAS_NUMBER)) and master pod is ready ($$RUNNING_MASTER_PODS/1)!"; \
			break; \
		fi; \
		echo "Waiting for pods to be ready... (Patroni: $$RUNNING_PATRONI_PODS/$(PATRONI_REPLICAS_NUMBER), Master: $$RUNNING_MASTER_PODS/1) - $$((TIMEOUT - ELAPSED_TIME))s remaining"; \
		sleep 10; \
	done
	@echo "$(GREEN)--- Stage 3 END: Patroni pods are ready$(NC)"
	@echo ""

# Install DBaaS Aggregator
.PHONY: install-dbaas-aggregator
install-dbaas-aggregator:
	@echo "$(BLUE)=== STAGE 4: DBaaS Aggregator Installation ===$(NC)"
	@echo "$(CYAN)--- Stage 4 BEGIN: Installing DBaaS Aggregator...$(NC)"
	@envsubst < $(DBAAS_VALUES_FILE) | helm upgrade --install -n $(DBAAS_NAMESPACE) $(CREATE_NAMESPACE_OPTION) \
		dbaas-aggregator $(REPOS_DIR)/qubership-dbaas/helm-templates/dbaas-aggregator -f - || \
		(echo "$(RED)Error: DBaaS Aggregator installation failed$(NC)" && echo "$(CYAN)Please check the helm output above for details$(NC)" && exit 1)
	@echo "$(GREEN)--- Stage 4 END: DBaaS Aggregator installation completed$(NC)"
	@echo ""

# Wait for DBaaS Aggregator service
.PHONY: wait-dbaas-aggregator
wait-dbaas-aggregator:
	@echo "$(BLUE)=== STAGE 5: Wait for DBaaS Aggregator Service ===$(NC)"
	@echo "$(CYAN)--- Stage 5 BEGIN: Waiting for dbaas-aggregator service to be ready (timeout: 5 minutes)...$(NC)"
	@TIMEOUT=300; START_TIME=$$(date +%s); \
	while true; do \
		CURRENT_TIME=$$(date +%s); \
		ELAPSED_TIME=$$((CURRENT_TIME - START_TIME)); \
		if [ $$ELAPSED_TIME -ge $$TIMEOUT ]; then \
			echo "$(RED)Error: Timeout reached after 5 minutes. Service is not ready.$(NC)"; \
			exit 1; \
		fi; \
		if kubectl -n $(DBAAS_NAMESPACE) get deployment dbaas-aggregator >/dev/null 2>&1; then \
			READY_REPLICAS=$$(kubectl -n $(DBAAS_NAMESPACE) get deployment dbaas-aggregator -o jsonpath='{.status.readyReplicas}' 2>/dev/null); \
			DESIRED_REPLICAS=$$(kubectl -n $(DBAAS_NAMESPACE) get deployment dbaas-aggregator -o jsonpath='{.spec.replicas}' 2>/dev/null); \
			if [ "$$READY_REPLICAS" = "$$DESIRED_REPLICAS" ] && [ ! -z "$$READY_REPLICAS" ]; then \
				echo "DBaaS Aggregator deployment is ready! ($$READY_REPLICAS/$$DESIRED_REPLICAS replicas)"; \
				break; \
			fi; \
		fi; \
		echo "Waiting for DBaaS Aggregator service to be ready... - $$((TIMEOUT - ELAPSED_TIME))s remaining"; \
		sleep 10; \
	done
	@echo "$(GREEN)--- Stage 5 END: DBaaS Aggregator service is ready$(NC)"
	@echo ""

# Install Patroni Services
.PHONY: install-patroni-services
install-patroni-services:
	@echo "$(BLUE)=== STAGE 6: Patroni Services Installation ===$(NC)"
	@echo "$(CYAN)--- Stage 6 BEGIN: Installing Patroni Services...$(NC)"
	@echo "$(BLUE)=== AUTO-DETECTING STORAGE CLASS ===$(NC)"
	@if [ -z "$(STORAGE_CLASS)" ]; then \
		echo "$(CYAN)--- STORAGE_CLASS not specified, detecting default storage class...$(NC)"; \
		DEFAULT_STORAGE_CLASS=$$(kubectl get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null); \
		if [ -z "$$DEFAULT_STORAGE_CLASS" ]; then \
			echo "$(CYAN)--- No default storage class found, checking for any available storage class...$(NC)"; \
			DEFAULT_STORAGE_CLASS=$$(kubectl get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
		fi; \
		if [ -z "$$DEFAULT_STORAGE_CLASS" ]; then \
			echo "$(RED)Error: No storage class found in cluster$(NC)"; \
			echo "$(CYAN)Please create a storage class or specify STORAGE_CLASS manually$(NC)"; \
			exit 1; \
		fi; \
		echo "$(GREEN)--- Auto-detected storage class: $$DEFAULT_STORAGE_CLASS$(NC)"; \
		echo "$(CYAN)--- Setting STORAGE_CLASS=$$DEFAULT_STORAGE_CLASS$(NC)"; \
		export STORAGE_CLASS=$$DEFAULT_STORAGE_CLASS; \
		echo "$(GREEN)--- Exported STORAGE_CLASS=$$STORAGE_CLASS$(NC)"; \
	else \
		echo "$(GREEN)--- STORAGE_CLASS already specified: $(STORAGE_CLASS)$(NC)"; \
	fi; \
	echo "$(GREEN)--- Final STORAGE_CLASS value: $$STORAGE_CLASS$(NC)"; \
	echo ""; \
	envsubst < $(PATRONI_SERVICES_VALUES_FILE) | helm upgrade --install --namespace=$(PG_NAMESPACE) $(CREATE_NAMESPACE_OPTION) $(SKIP_CRDS_OPTION) \
		patroni-services $(REPOS_DIR)/pgskipper-operator/operator/charts/patroni-services -f - || \
		(echo "$(RED)Error: Patroni Services installation failed$(NC)" && echo "$(CYAN)Please check the helm output above for details$(NC)" && exit 1)
	@echo "$(GREEN)--- Stage 6 END: Patroni Services installation completed$(NC)"
	@echo ""

# Wait for Patroni Services registration
.PHONY: wait-patroni-services-registration
wait-patroni-services-registration:
	@echo "$(BLUE)=== STAGE 7: Wait for Patroni Services Registration ===$(NC)"
	@echo "$(CYAN)--- Stage 7 BEGIN: Waiting for patroni services registration (timeout: 5 minutes)...$(NC)"
	@TIMEOUT=300; START_TIME=$$(date +%s); \
	while true; do \
		CURRENT_TIME=$$(date +%s); \
		ELAPSED_TIME=$$((CURRENT_TIME - START_TIME)); \
		if [ $$ELAPSED_TIME -ge $$TIMEOUT ]; then \
			echo "$(RED)Error: Timeout reached after 5 minutes. Registration message not found in logs.$(NC)"; \
			exit 1; \
		fi; \
		if kubectl logs -n $(PG_NAMESPACE) -l app=dbaas-postgres-adapter --tail=100 | grep -q "Registration finished"; then \
			echo "$(GREEN)=== Patroni services registration completed$(NC)"; \
			break; \
		fi; \
		echo "Waiting for patroni services registration... - $$((TIMEOUT - ELAPSED_TIME))s remaining"; \
		sleep 10; \
	done
	@echo "$(GREEN)--- Stage 7 END: Patroni services registration completed$(NC)"
	@echo ""

# Main install target
.PHONY: install
install: install-without-test smoke-test
	@echo "$(GREEN)=== INSTALLATION AND SMOKE TEST COMPLETED SUCCESSFULLY ===$(NC)"

# Install without smoke test
.PHONY: install-without-test
install-without-test: validate show-config setup-repos install-patroni-core wait-patroni-pods install-dbaas-aggregator wait-dbaas-aggregator install-patroni-services wait-patroni-services-registration
	@echo "$(GREEN)=== ALL STAGES COMPLETED SUCCESSFULLY ===$(NC)"
	@echo ""

# Smoke test - Test DB creation via REST API
.PHONY: smoke-test
smoke-test:
	@echo "$(BLUE)=== SMOKE TEST: Database Creation via REST API (after 30s sleep)===$(NC)"
	@sleep 30;
	@echo "$(CYAN)--- Stage BEGIN: Testing database creation via REST API...$(NC)"
	@echo "$(CYAN)--- Waiting for DBaaS service to be ready...$(NC)"
	@TIMEOUT=300; START_TIME=$$(date +%s); \
	while true; do \
		CURRENT_TIME=$$(date +%s); \
		ELAPSED_TIME=$$((CURRENT_TIME - START_TIME)); \
		if [ $$ELAPSED_TIME -ge $$TIMEOUT ]; then \
			echo "$(RED)Error: Timeout reached after 5 minutes. Service is not ready.$(NC)"; \
			exit 1; \
		fi; \
		if kubectl -n $(DBAAS_NAMESPACE) get service $(DBAAS_SERVICE_NAME) >/dev/null 2>&1; then \
			echo "$(GREEN)--- DBaaS service is ready$(NC)"; \
			break; \
		fi; \
		echo "Waiting for DBaaS service to be ready... - $$((TIMEOUT - ELAPSED_TIME))s remaining"; \
		sleep 10; \
	done
	@echo "$(CYAN)--- Getting service credentials...$(NC)"
	@DBAAS_CREDENTIALS_SECRET_NAME=dbaas-aggregator-registration-credentials; \
	echo "$(CYAN)--- Getting credentials from secret $$DBAAS_CREDENTIALS_SECRET_NAME in namespace $(PG_NAMESPACE)...$(NC)"; \
	DBAAS_USERNAME=$$(kubectl -n $(PG_NAMESPACE) get secret $$DBAAS_CREDENTIALS_SECRET_NAME -o jsonpath='{.data.username}'  | base64 -d); \
	DBAAS_PASSWORD=$$(kubectl -n $(PG_NAMESPACE) get secret $$DBAAS_CREDENTIALS_SECRET_NAME -o jsonpath='{.data.password}'  | base64 -d); \
	if [ -z "$$DBAAS_USERNAME" ] || [ -z "$$DBAAS_PASSWORD" ]; then \
		echo "$(RED)Error: Could not retrieve DBaaS credentials$(NC)"; \
		exit 1; \
	fi; \
	start_port_forward() { \
		port=$$1; \
		kubectl port-forward -n $(DBAAS_NAMESPACE) service/$(DBAAS_SERVICE_NAME) $$port:8080 >/tmp/dbaas-port-forward.log 2>&1 & \
		PORT_FORWARD_PID=$$!; \
		echo "$(CYAN)--- Port forward started with PID: $$PORT_FORWARD_PID$(NC)"; \
		sleep 2; \
		if ! kill -0 $$PORT_FORWARD_PID 2>/dev/null; then \
			echo "$(RED)Error: Port forward process exited. See /tmp/dbaas-port-forward.log$(NC)"; \
			return 1; \
		fi; \
		if ! timeout 2 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/$$port" >/dev/null 2>&1; then \
			echo "$(RED)Error: Port $$port not listening after port-forward start$(NC)"; \
			return 1; \
		fi; \
		return 0; \
	}; \
	echo "$(GREEN)--- Credentials retrieved successfully$(NC)"; \
	echo "$(CYAN)--- Setting up port forward to DBaaS service...$(NC)";\
	PORT_FOUND=false;\
	ATTEMPT=1;\
	while [ $$ATTEMPT -le 10 ] && [ "$$PORT_FOUND" != "true" ]; do \
		RANDOM_PORT=$$(shuf -i 63000-65900 -n 1); \
		echo "$(CYAN)--- Attempt $$ATTEMPT: Trying port $$RANDOM_PORT$(NC)"; \
		if ! netstat -tuln 2>/dev/null | grep -q ":$$RANDOM_PORT "; then \
			echo "$(GREEN)--- Port $$RANDOM_PORT is available$(NC)"; \
			if start_port_forward $$RANDOM_PORT; then \
				PORT_FOUND=true; \
			else \
				kill $$PORT_FORWARD_PID 2>/dev/null || true; \
				ATTEMPT=$$((ATTEMPT + 1)); \
			fi; \
		else \
			echo "$(CYAN)--- Port $$RANDOM_PORT is already in use, trying another port$(NC)"; \
			ATTEMPT=$$((ATTEMPT + 1)); \
		fi; \
	done; \
	if [ "$$PORT_FOUND" != "true" ]; then \
		echo "$(RED)Error: Could not find an available port after 10 attempts$(NC)"; \
		exit 1; \
	fi; \
	sleep 10;\
	echo "$(CYAN)--- Creating test database via REST API (10 attempts with 10s sleep)...$(NC)";\
	BASIC_AUTH_HEADER=$$(echo -n "$$DBAAS_USERNAME:$$DBAAS_PASSWORD" | base64);\
	DB_CREATION_SUCCESS=false;\
	DB_ATTEMPT=1;\
	while [ $$DB_ATTEMPT -le 10 ] && [ "$$DB_CREATION_SUCCESS" != "true" ]; do \
		echo "$(CYAN)--- Database creation attempt $$DB_ATTEMPT/10...$(NC)"; \
		TEST_DB_RESPONSE=$$(curl -s -w "HTTPSTATUS:%{http_code}" -X PUT \
			"http://localhost:$$RANDOM_PORT/api/v3/dbaas/$(DBAAS_NAMESPACE)/databases" \
			-H "Content-Type: application/json" \
			-H "Authorization: Basic $$BASIC_AUTH_HEADER" \
			-d '{ \
				"classifier": { \
					"microserviceName": "smoke-test-service", \
					"scope": "service", \
					"namespace": "$(DBAAS_NAMESPACE)" \
				}, \
				"type": "postgresql", \
				"originService": "smoke-test-service" \
			}' 2>/dev/null); \
		HTTP_STATUS=$$(echo "$$TEST_DB_RESPONSE" | grep -o "HTTPSTATUS:[0-9]*" | cut -d: -f2); \
		RESPONSE_BODY=$$(echo "$$TEST_DB_RESPONSE" | sed 's/HTTPSTATUS:[0-9]*//'); \
		if [ -z "$$HTTP_STATUS" ] || [ "$$HTTP_STATUS" = "000" ]; then \
			echo "$(CYAN)--- Detected HTTPSTATUS 000/empty. Restarting port forward...$(NC)"; \
			kill $$PORT_FORWARD_PID 2>/dev/null || true; \
			if ! start_port_forward $$RANDOM_PORT; then \
				echo "$(RED)Error: Failed to restart port forward$(NC)"; \
			fi; \
		fi; \
		if [ "$$HTTP_STATUS" = "200" ] || [ "$$HTTP_STATUS" = "201" ] || [ "$$HTTP_STATUS" = "202" ]; then \
			echo "$(GREEN)--- Database creation successful on attempt $$DB_ATTEMPT! HTTP Status: $$HTTP_STATUS$(NC)"; \
			echo "$(CYAN)--- Response: $$RESPONSE_BODY$(NC)"; \
			DB_CREATION_SUCCESS=true; \
		else \
			echo "$(CYAN)--- Database creation attempt $$DB_ATTEMPT failed. HTTP Status: $$HTTP_STATUS$(NC)"; \
			echo "$(CYAN)--- Response: $$RESPONSE_BODY$(NC)"; \
			if [ $$DB_ATTEMPT -lt 10 ]; then \
				echo "$(CYAN)--- Waiting 10 seconds before next attempt...$(NC)"; \
				sleep 10; \
			fi; \
			DB_ATTEMPT=$$((DB_ATTEMPT + 1)); \
		fi; \
	done; \
	if [ "$$DB_CREATION_SUCCESS" != "true" ]; then \
		echo "$(RED)Error: Database creation failed after 10 attempts$(NC)"; \
		echo "$(CYAN)--- Cleaning up: Removing port forward...$(NC)"; \
		kill $$PORT_FORWARD_PID 2>/dev/null || true; \
		echo "$(GREEN)--- Cleaning up: Port forward removed$(NC)"; \
		exit 1; \
	fi; \
	echo "$(CYAN)--- Cleaning up: Removing test database...$(NC)"; \
	DELETE_RESPONSE=$$(curl -s -w "HTTPSTATUS:%{http_code}" -X DELETE \
		"http://localhost:$$RANDOM_PORT/api/v3/dbaas/$(DBAAS_NAMESPACE)/databases/postgresql" \
		-H "Content-Type: application/json" \
		-H "Authorization: Basic $$BASIC_AUTH_HEADER" \
		-d '{ \
			"classifier": { \
				"microserviceName": "smoke-test-service", \
				"scope": "service", \
				"namespace": "$(DBAAS_NAMESPACE)" \
			}, \
			"originService": "smoke-test-service" \
		}' 2>/dev/null); \
	DELETE_STATUS=$$(echo "$$DELETE_RESPONSE" | grep -o "HTTPSTATUS:[0-9]*" | cut -d: -f2); \
	DELETE_BODY=$$(echo "$$DELETE_RESPONSE" | sed 's/HTTPSTATUS:[0-9]*//'); \
	if [ "$$DELETE_STATUS" = "200" ] || [ "$$DELETE_STATUS" = "204" ]; then \
		echo "$(GREEN)--- Database removal successful! HTTP Status: $$DELETE_STATUS$(NC)"; \
	else \
		echo "$(RED)--- Database removal failed (this is not critical): HTTP Status: $$DELETE_STATUS$(NC)"; \
		echo "$(CYAN)--- Response: $$DELETE_BODY$(NC)"; \
	fi; \
	echo "$(CYAN)--- Cleaning up: Removing port forward...$(NC)"; \
	kill $$PORT_FORWARD_PID 2>/dev/null || true; \
	echo "$(GREEN)--- Cleaning up: Port forward removed$(NC)"; \
	echo "$(GREEN)--- Stage END: Smoke test completed successfully$(NC)"; \
	echo ""

# Uninstall targets
.PHONY: uninstall-patroni-services
uninstall-patroni-services:
	@echo "$(BLUE)=== STAGE 1: Uninstall Patroni Services ===$(NC)"
	@echo "$(CYAN)--- Stage 1 BEGIN: Uninstalling Patroni Services...$(NC)"
	@helm uninstall -n $(PG_NAMESPACE) patroni-services 2>/dev/null || echo "Patroni Services not found or already uninstalled"
	@echo "$(GREEN)--- Stage 1 END: Patroni Services uninstalled$(NC)"
	@echo ""

.PHONY: uninstall-dbaas-aggregator
uninstall-dbaas-aggregator:
	@echo "$(BLUE)=== STAGE 2: Uninstall DBaaS Aggregator ===$(NC)"
	@echo "$(CYAN)--- Stage 2 BEGIN: Uninstalling DBaaS Aggregator...$(NC)"
	@helm uninstall -n $(DBAAS_NAMESPACE) dbaas-aggregator 2>/dev/null || echo "DBaaS Aggregator not found or already uninstalled"
	@echo "$(CYAN)--- Uninstalling DBaaS Aggregator secrets created by scripts, not by helm...$(NC)"
	@echo "$(CYAN)--- (dbaas-aggregator-env-variables has dynamic resource name and not cleaned by helm)$(NC)"
	@kubectl delete secret $(DBAAS_SERVICE_NAME)-encryption-secret -n $(DBAAS_NAMESPACE) 2>/dev/null || echo "Secret dbaas-aggregator-encryption-secret not found or already deleted"
	@kubectl delete secret $(DBAAS_SERVICE_NAME)-env-variables -n $(DBAAS_NAMESPACE) 2>/dev/null || echo "Secret dbaas-aggregator-env-variables not found or already deleted"
	@kubectl delete secret dbaas-storage-credentials -n $(DBAAS_NAMESPACE) 2>/dev/null || echo "Secret dbaas-storage-credentials not found or already deleted"
	@echo "$(GREEN)--- Stage 2 END: DBaaS Aggregator uninstalled$(NC)"
	@echo ""

.PHONY: uninstall-patroni-core
uninstall-patroni-core:
	@echo "$(BLUE)=== STAGE 3: Uninstall Patroni Core ===$(NC)"
	@echo "$(CYAN)--- Stage 3 BEGIN: Uninstalling Patroni Core...$(NC)"
	@helm uninstall -n $(PG_NAMESPACE) patroni-core 2>/dev/null || echo "Patroni Core not found or already uninstalled"
	@echo "$(GREEN)--- Stage 3 END: Patroni Core uninstalled$(NC)"
	@echo ""

# Main uninstall target
.PHONY: uninstall
uninstall: show-config uninstall-patroni-services uninstall-dbaas-aggregator uninstall-patroni-core manual-cleanup cleanup-crds cleanup-namespaces cleanup-dbaas-node-label
	@echo "$(GREEN)=== ALL COMPONENTS UNINSTALLED SUCCESSFULLY ===$(NC)"

# Clean repositories
.PHONY: clean
clean:
	@echo "$(CYAN)--- Cleaning up repositories...$(NC)"
	@rm -rf $(REPOS_DIR)/pgskipper-operator $(REPOS_DIR)/qubership-dbaas
	@echo "$(GREEN)--- Repositories cleaned$(NC)"

# remove DBaaS label from nodes
.PHONY: cleanup-dbaas-node-label
cleanup-dbaas-node-label:
	@if [ "$(ADD_DBAAS_NODE_LABEL)" = "true" ]; then \
		echo "$(CYAN)--- Finding nodes with label '$(NODE_SELECTOR_DBAAS_KEY)'...$(NC)"; \
		echo "$(CYAN)--- Searching for nodes with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)'...$(NC)"; \
		NODES_WITH_LABEL=$$(kubectl get nodes -l $(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS) --no-headers 2>/dev/null); \
		if [ -n "$$NODES_WITH_LABEL" ]; then \
			echo "$(GREEN)--- Found nodes with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)':$(NC)"; \
			echo "$$NODES_WITH_LABEL"; \
			echo "$(CYAN)--- Removing label '$(NODE_SELECTOR_DBAAS_KEY)' from nodes...$(NC)"; \
			kubectl label nodes -l $(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS) $(NODE_SELECTOR_DBAAS_KEY)-; \
			echo "$(GREEN)--- Label removed successfully$(NC)"; \
		else \
			echo "$(YELLOW)--- No nodes found with label '$(NODE_SELECTOR_DBAAS_KEY)=$(REGION_DBAAS)'$(NC)"; \
		fi; \
	else \
		echo "$(YELLOW)--- Skipping DBaaS node label cleanup (ADD_DBAAS_NODE_LABEL=false)...$(NC)"; \
	fi
	@echo ""

# Manual cleanup target for additional resources
.PHONY: manual-cleanup
manual-cleanup:
	@echo "$(CYAN)--- Manual cleanup of additional resources...$(NC)"
	@echo "$(CYAN)--- Note: Patroni creates config maps, PVCs, services that are not cleaned by helm$(NC)"
	@echo "$(CYAN)--- kubectl delete configmap -n $(PG_NAMESPACE) -l app=patroni$(NC)"
	@kubectl delete configmap -n $(PG_NAMESPACE) -l app=patroni || echo "Config map not found or already deleted"
	@echo "$(CYAN)--- kubectl delete pvc -n $(PG_NAMESPACE) --all$(NC)"
	@kubectl delete pvc -n $(PG_NAMESPACE) --all || echo "PVCs not found or already deleted"
	@echo "$(CYAN)--- kubectl delete pv with claim from namespace $(PG_NAMESPACE)$(NC)"
	@kubectl get pv -o custom-columns=name:metadata.name --no-headers 2>/dev/null | \
		xargs -I {} sh -c 'kubectl get pv {} -o jsonpath="{.spec.claimRef.namespace}" | \
		grep -q "^$(PG_NAMESPACE)$$" && echo {}' | \
		xargs -r kubectl delete pv || echo "PVs not found or already deleted"	
	@echo "$(CYAN)--- kubectl delete service -n $(PG_NAMESPACE) --all$(NC)"
	@kubectl delete service -n $(PG_NAMESPACE) --all || echo "Service not found or already deleted"
	@echo "$(GREEN)--- Manual cleanup completed$(NC)"
	@echo ""

.PHONY: cleanup-crds
cleanup-crds:
	@echo "$(CYAN)--- Cleaning up CRDs...$(NC)"
	@if [ "$(SKIP_CRDS)" = "false" ]; then \
		echo " CRDs..."; \
		kubectl delete crd patronicores.netcracker.com --ignore-not-found=true; \
		kubectl delete crd patroniservices.netcracker.com --ignore-not-found=true; \
		echo ""; \
	else \
		echo "$(GREEN)--- Skipping CRD cleanup (SKIP_CRDS=true)...$(NC)"; \
		echo ""; \
	fi

.PHONY: cleanup-namespaces
cleanup-namespaces:
	@echo "$(CYAN)--- Cleaning up namespaces...$(NC)"
	@if [ "$(CREATE_NAMESPACE)" = "true" ]; then \
		echo "Removing namespaces..."; \
		kubectl delete namespace $(DBAAS_NAMESPACE) --ignore-not-found=true; \
		kubectl delete namespace $(PG_NAMESPACE) --ignore-not-found=true; \
		echo ""; \
	else \
		echo "$(GREEN)--- Skipping namespace cleanup (CREATE_NAMESPACE=false)...$(NC)"; \
		echo ""; \
	fi

	
