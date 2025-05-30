#!/usr/bin/env bash

create_secret() {
  echo "Start secret creation."
cat << EOF | kubectl apply -f - --namespace="${NAMESPACE}"
{
      "apiVersion": "v1",
      "kind": "Secret",
      "metadata": {
        "name": "dbaas-storage-credentials"
      },
      "data": {
        "username": "$(printf "${2}" | base64 -w 0)",
        "password": "$(printf "${3}" | base64 -w 0)",
        "database": "$(printf "${1}" | base64 -w 0)"
      }
    }
EOF
  echo "End secret creation."
}

create_db() {
  local host=$1
  local port=$2
  local database=$3
  local db_owner_user_name=$4
  local user_name=$5
  local user_pass=$6
  echo "start create db with host=${host}; port=${port}; database=${database}; db_owner_user_name=${db_owner_user_name}; user_name=${user_name}"
  psql "host=${host} port=${port} dbname=postgres user=${user_name} password=${user_pass}" -tc "SELECT 1 FROM pg_database WHERE datname = '$database'" | grep -q 1 || psql "host=${host} port=${port} dbname=postgres user=${user_name} password=${user_pass}" -c "CREATE DATABASE $database"
  if [ $? -ne 0 ]; then
    echo "database creation error"
    [[ $USE_POSTGRES_PORT_FORWARD == 'true' ]] && kill_process "${PORT_FORWARD_PID}"
    exit 121
  fi
  psql "host=${host} port=${port} dbname=postgres user=${user_name} password=${user_pass}" -c "grant all privileges on database $database to $db_owner_user_name;"
  psql "host=${host} port=${port} dbname=${database} user=${user_name} password=${user_pass}" -c "GRANT ALL ON SCHEMA public TO $db_owner_user_name;"
}

kill_process(){
    local process_pid="${1}"
    if [[ ! -z "${process_pid}" ]];then
        echo "Process with pid=${process_pid} will be killed"
        kill ${process_pid}
    else
        echo "Process pid is empty"
    fi
}

isSecretExist() {
  echo "Entered isSecretExist func with secret name ${1}"
  SECRET=$(kubectl get secret $1 --output=go-template='{{ .metadata.name }}')

  if [ -z $SECRET ]
  then
    secretExists='false'
  else
    secretExists='true'
  fi
}

ensure-encryption-secret() {
  echo "Check [encryption-secret]"
  service=${ENV_SERVICE:=dbaas-aggregator}

  # 1. Secrets
  # 1.1 Get secret SYM_KEY_VALUE
  SECRET=$(kubectl get secret ${service}-encryption-secret --namespace="$ENV_NAMESPACE" --output=go-template='{{ .metadata.name }}')

  # 1.2 Check secrets
  if [ -z $SECRET ]
  then
     echo "The secret [encryption-secret]" not found!

     #Generate symmetric key
     SYM_KEY_VALUE=$(python3 -c "\
import base64; \
from Crypto import Random; \
symkey = base64.b64encode(Random.new().read(16)).decode(\"utf-8\"); \
print(symkey);")

     echo "SYM_KEY: ${SYM_KEY_VALUE}"
      SYM_KEY_VALUE_B64=$(echo ${SYM_KEY_VALUE}|base64)
     echo "SYM_KEY_B64: ${SYM_KEY_VALUE_B64}"

     #Generate and encrypt default key
     DEFAULT_KEY_VALUE=$(python3 -c "\
import base64; \
import binascii; \
from Crypto.Cipher import AES; \
from Crypto import Random; \
 \
BS = 16; \
pad = lambda s: s + (BS - len(s) % BS) * bytes([BS - len(s) % BS]); \
\
symkey = base64.b64decode(\"${SYM_KEY_VALUE}\"); \
defaultkey = pad(Random.new().read(16)); \
cipher = AES.new(symkey, AES.MODE_ECB); \
\
encryptedDefaultKey = base64.b64encode(cipher.encrypt(defaultkey)).decode(\"utf-8\"); \
print(encryptedDefaultKey);")

     echo "DEFAULT_KEY: $DEFAULT_KEY_VALUE"
     DEFAULT_KEY_VALUE_B64=$(echo ${DEFAULT_KEY_VALUE}|base64)
     echo "DEFAULT_KEY_B64: ${DEFAULT_KEY_VALUE_B64}"

     echo

  # 1.3 Create secret json
  SECRET_JSON=$(echo "\
{ \
  \"apiVersion\": \"v1\", \
  \"kind\": \"Secret\", \
  \"metadata\": { \
    \"name\": \"${service}-encryption-secret\" \
  }, \
  \"data\": { \
    \"sym-key\": \"${SYM_KEY_VALUE_B64}\", \
    \"default-key\": \"${DEFAULT_KEY_VALUE_B64}\" \
  } \
}")

  echo "Secret in JSON: $SECRET_JSON"

  #exit

  # 1.4 Create secret from json
  echo "$SECRET_JSON" | kubectl apply -f - --namespace="${ENV_NAMESPACE}"
  else
     echo "The Secret [$SECRET] already exists."
  fi
}

ensure-encryption-secret

export PGSSLMODE=allow # allows to work with postgres in both ssl and not ssl modes
POSTGRES_HOST="${POSTGRES_HOST:-"pg-patroni.postgresql"}"
POSTGRES_PORT="${POSTGRES_PORT:-"5432"}"
POSTGRES_DBA_USER="${POSTGRES_DBA_USER:-"postgres"}"
POSTGRES_DBA_PASSWORD="${POSTGRES_DBA_PASSWORD:-"paSSw0rd"}"
USE_POSTGRES_PORT_FORWARD=$(echo "${USE_POSTGRES_PORT_FORWARD:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
IFS='.' read -r -a array <<< "${POSTGRES_HOST}"
pg_namespace=${array[1]}

secretName="dbaas-storage-credentials"
secretExists=''
isSecretExist "${secretName}"
if [[ ${secretExists} == 'true' ]]; then
  echo "Secret with name \"dbaas-storage-credentials\" exists. Skip prepare pg database"
  exit 0
fi

DBAAS_OWN_PG_DB_CREATED_MANUALLY=$(echo "${DBAAS_OWN_PG_DB_CREATED_MANUALLY:-false}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')

if [[ "${DBAAS_OWN_PG_DB_CREATED_MANUALLY}" == 'true' ]] && [[ ! -z "${POSTGRES_DBAAS_USER}" ]] && [[ ! -z "${POSTGRES_DBAAS_PASSWORD}" ]] && [[ ! -z "${POSTGRES_DBAAS_DATABASE_NAME}" ]]; then
    echo "Param DBAAS_OWN_PG_DB_CREATED_MANUALLY is ${DBAAS_OWN_PG_DB_CREATED_MANUALLY}; use user ${POSTGRES_DBAAS_USER} own db ${POSTGRES_DBAAS_DATABASE_NAME}, skip prepare database script"
    create_secret ${POSTGRES_DBAAS_DATABASE_NAME} ${POSTGRES_DBAAS_USER} ${POSTGRES_DBAAS_PASSWORD}
    exit 0
fi

echo "pg host: ${POSTGRES_HOST}, pg port: ${POSTGRES_PORT}, pg admin username: ${POSTGRES_DBA_USER}, pg dbaas username: ${POSTGRES_DBAAS_USER}"

if [[ $USE_POSTGRES_PORT_FORWARD == 'true' ]]; then
    pod_name=$(kubectl get pods --selector='pgtype=master' --output=go-template='{{ (index .items 0).metadata.name  }}' --namespace="${pg_namespace}")
    echo "pg master pod name ${pod_name}"
    while :; do connection_port="`shuf -i 32768-60999 -n 1`"; ss -lpn | grep -q ":$connection_port " || break; done
    kubectl port-forward ${pod_name} "${connection_port}:${POSTGRES_PORT}" --request-timeout=20s --namespace=${pg_namespace} > /dev/null & # port-forward does not work in deploer 6.x using custom plugin
    PORT_FORWARD_PID="$!"
    sleep 20
    connection_host="localhost"
else
    echo 'Use external PostgreSQL instance'
    connection_host=$POSTGRES_HOST
    connection_port=$POSTGRES_PORT
    echo
fi

pgDatabase=${POSTGRES_DBAAS_DATABASE_NAME}
if [[ -z "${pgDatabase}" ]]; then
  pgDatabase=$(echo ${NAMESPACE} | tr '-' '_')
fi
if [[ ! -z "${POSTGRES_DBAAS_USER}" ]] && [[ ! -z "${POSTGRES_DBAAS_PASSWORD}" ]]; then
  echo "Create db as dbaas user"
  create_db ${connection_host} ${connection_port} ${pgDatabase} ${POSTGRES_DBAAS_USER} ${POSTGRES_DBAAS_USER} ${POSTGRES_DBAAS_PASSWORD}
  create_secret ${pgDatabase} ${POSTGRES_DBAAS_USER} ${POSTGRES_DBAAS_PASSWORD}
elif [[ ! -z "${POSTGRES_DBA_USER}" ]] && [[ ! -z "${POSTGRES_DBA_PASSWORD}" ]]; then
  echo "Create db as dba user"
  PG_USER=$(echo "dbaas_user_${NAMESPACE}" | tr '-' '_')
  PG_PASSWORD=$(tr < /dev/urandom -dc _A-Za-z0-9 | head -c16)
  IS_USER_EXISTS=$(psql "host=${connection_host} port=${connection_port} dbname=postgres user=${POSTGRES_DBA_USER} password=${POSTGRES_DBA_PASSWORD}" -tAc "SELECT 1 FROM pg_roles WHERE rolname='${PG_USER}'")
  if [[ -z "${IS_USER_EXISTS}" ]]; then
    psql "host=${connection_host} port=${connection_port} dbname=postgres user=${POSTGRES_DBA_USER} password=${POSTGRES_DBA_PASSWORD}" -c "create user ${PG_USER} with encrypted password '${PG_PASSWORD}';"
  else
    echo "Postgres user ${PG_USER} already exists, so you should pass dbaas specific user through POSTGRES_DBAAS_USER:POSTGRES_DBAAS_PASSWORD parameters"
    [[ $USE_POSTGRES_PORT_FORWARD == 'true' ]] && kill_process "${PORT_FORWARD_PID}"
    exit 121
  fi
  create_db ${connection_host} ${connection_port} ${pgDatabase} ${PG_USER} ${POSTGRES_DBA_USER} ${POSTGRES_DBA_PASSWORD}
  create_secret ${pgDatabase} ${PG_USER} ${PG_PASSWORD}
else
  echo "Dbaas needs a postgres dbaas user for storing information, so you should pass dbaas specific user through POSTGRES_DBAAS_USER:POSTGRES_DBAAS_PASSWORD parameters
  or pass admin user through POSTGRES_DBA_USER:POSTGRES_DBA_PASSWORD for creating this user automatically"
  [[ $USE_POSTGRES_PORT_FORWARD == 'true' ]] && kill_process "${PORT_FORWARD_PID}"
  exit 121
fi
[[ $USE_POSTGRES_PORT_FORWARD == 'true' ]] && kill_process "${PORT_FORWARD_PID}"
exit 0
