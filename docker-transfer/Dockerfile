# Transfer image: the DBaaS Helm charts as stored in this repository, as data only.
#
#   /charts/dbaas-aggregator  - helm-templates/dbaas-aggregator
#   /charts/dbaas-operator    - dbaas-operator/helm-templates/dbaas-operator

FROM scratch

COPY helm-templates/dbaas-aggregator /charts/dbaas-aggregator
COPY dbaas-operator/helm-templates/dbaas-operator /charts/dbaas-operator
