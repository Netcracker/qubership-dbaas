#!/bin/bash

readonly ERROR_EXIT_CODE=122

#read arguments and set envs
arguments=("$@")
for i in "${arguments[@]}"; do
  IS_ENV=$(echo "${i}" | grep '=' -c || :)
  IS_EMPTY_ENV=$(echo "${i}" | grep '=$' -c || :)
  if [ "${IS_ENV}" -eq 1 ] && [ "${IS_EMPTY_ENV}" -eq 0 ]; then
    export "${i}"
  fi
done

[[ "${DEBUG}" == 'true' ]] && set -x

if [ -n "${SKIP_VALIDATION}" ]; then
  SKIP_VALIDATION_LOWERCASE=$(echo "${SKIP_VALIDATION}" | tr '[:upper:]' '[:lower:]')
  if [ "${SKIP_VALIDATION_LOWERCASE}" = 'true' ]; then
    echo 'SKIP VALIDATION DBAAS-AGGREGATOR'
    exit 0
  fi
fi

####################

check_postgres() {
  MAX_RETRY_COUNT=36
  RETRY_DELAY_SECONDS=5

  echo "Start to check postgresql's parameters..."
  POSTGRES_PORT="${POSTGRES_PORT:-"5432"}"

  SUCCESSFUL_PG_CONNECT=false
  CURRENT_RETRY=1
  while
    if (( CURRENT_RETRY > 1 )); then
      sleep $RETRY_DELAY_SECONDS
    fi

    echo "Try to reach PostgreSQL host, attempt $CURRENT_RETRY"
    (echo > /dev/tcp/${POSTGRES_HOST}/${POSTGRES_PORT}) 2>/dev/null
    if [ $(echo $?) == 0 ]; then
      SUCCESSFUL_PG_CONNECT=true
    fi

    ((CURRENT_RETRY++))
    [[ ($SUCCESSFUL_PG_CONNECT == false) && ($CURRENT_RETRY -le $MAX_RETRY_COUNT) ]]
  do true; done

  if [[ $SUCCESSFUL_PG_CONNECT == false ]]; then
    echo "ERROR! CHECK FAILED!"
    echo "Wrong parameter:"
    echo "POSTGRES_HOST=${POSTGRES_HOST}"
    echo "POSTGRES_PORT=${POSTGRES_PORT}"
    exit ${ERROR_EXIT_CODE}
  fi
  echo 'Params POSTGRES_HOST, POSTGRES_PORT are correct'

  POSTGRES_USER=${POSTGRES_DBAAS_USER}
  POSTGRES_PASSWORD=${POSTGRES_DBAAS_PASSWORD}
  check_dba_password="false"
  if [[ -z "${POSTGRES_USER}" ]] || [[ -z "${POSTGRES_PASSWORD}" ]]; then
    echo 'Params POSTGRES_DBAAS_USER, POSTGRES_DBAAS_PASSWORD are empty, using POSTGRES_DBA_USER, POSTGRES_DBA_PASSWORD instead'
    POSTGRES_USER=${POSTGRES_DBA_USER}
    POSTGRES_PASSWORD=${POSTGRES_DBA_PASSWORD}
    check_dba_password="true"
  fi

  DBAAS_OWN_PG_DB_CREATED_MANUALLY=$(echo "${DBAAS_OWN_PG_DB_CREATED_MANUALLY}" | tr '[:upper:]' '[:lower:]')
  if [ -z ${DBAAS_OWN_PG_DB_CREATED_MANUALLY} ] || [ "${DBAAS_OWN_PG_DB_CREATED_MANUALLY}" = "false" ]; then
    SUCCESSFUL_PG_CONNECT=false
    CURRENT_RETRY=1
    while
      if (( CURRENT_RETRY > 1 )); then
        sleep $RETRY_DELAY_SECONDS
      fi

      echo "Try to create test DB, attempt $CURRENT_RETRY"
      CHECK_PG_USERNAME_PASSWORD=$(psql "host=${POSTGRES_HOST} port=${POSTGRES_PORT} user=${POSTGRES_USER} password=${POSTGRES_PASSWORD} dbname=postgres" -tc "CREATE DATABASE validation_check_database" || echo FAIL)
      if [ "${CHECK_PG_USERNAME_PASSWORD}" != "FAIL" ]; then
        SUCCESSFUL_PG_CONNECT=true
      fi

      ((CURRENT_RETRY++))
      [[ ($SUCCESSFUL_PG_CONNECT == false) && ($CURRENT_RETRY -le $MAX_RETRY_COUNT) ]]
    do true; done

    if [[ $SUCCESSFUL_PG_CONNECT == false ]]; then
      echo "ERROR! CHECK FAILED!"
      echo "It is not possible to create a database from the user:"
      echo "POSTGRES_USER=${POSTGRES_USER}"
      echo "POSTGRES_PASSWORD=*****"
      exit ${ERROR_EXIT_CODE}
    fi
    psql -q "host=${POSTGRES_HOST} port=${POSTGRES_PORT} user=${POSTGRES_USER} password=${POSTGRES_PASSWORD} dbname=postgres" -tc "DROP DATABASE IF EXISTS validation_check_database"
  fi

  if [ "${DBAAS_OWN_PG_DB_CREATED_MANUALLY}" = "true" ]; then
    if [[ -z ${POSTGRES_DBAAS_DATABASE_NAME} ]]; then
      echo "ERROR! CHECK FAILED!"
      echo "POSTGRES_DBAAS_DATABASE_NAME must be set if DBAAS_OWN_PG_DB_CREATED_MANUALLY=true"
      exit ${ERROR_EXIT_CODE}
    fi
    IS_DB_EXISTS=$(psql -q "host=${POSTGRES_HOST} port=${POSTGRES_PORT} user=${POSTGRES_USER} password=${POSTGRES_PASSWORD} dbname=${POSTGRES_DBAAS_DATABASE_NAME}" -tc "SELECT 1" || echo FAIL)
    if [ "${IS_DB_EXISTS}" = "FAIL" ]; then
      echo "ERROR! CHECK FAILED!"
      echo "POSTGRES_DBAAS_DATABASE_NAME=${POSTGRES_DBAAS_DATABASE_NAME} does not exists."
      exit ${ERROR_EXIT_CODE}
    fi
  fi
  echo 'Params POSTGRES_USER, POSTGRES_PASSWORD are correct'

  echo "Checking postgresql's parameters is completed."
}


check_prereq(){
    echo | jq "."
    EXIT_CODE=$?
    if [[ "$EXIT_CODE" -ne 0 ]]; then
        echo 'Error'
        echo 'jq is not installed!'
        echo 'Please install jq'
        echo
        exit 122
    fi

    kubectl -h > /dev/null
    EXIT_CODE=$?
    if [[ "$EXIT_CODE" -ne 0 ]]; then
        echo 'Error'
        echo "kubectl is not installed!"
        echo "Please install kubectl"
        echo
        exit 122
    fi
}

#########################

echo 'Checking parameters of Dbaas-aggregator is starting...'

check_prereq
check_postgres

echo 'Checking parameters of Dbaas-aggregator was completed successfully!'

exit 0
