# Transfer image carrying the DBaaS Helm charts to the internal (GitLab) pipeline.
#
# The image ships the charts exactly as stored in the repository:
#
#   /charts/dbaas-aggregator  - the aggregator chart (helm-templates/dbaas-aggregator)
#   /charts/dbaas-operator    - the operator chart, still containing its crd-*.yaml templates
#   /charts/dbaas-crd         - the CRD-only chart derived from the operator chart
#
# CRD definitions are canonical in the operator chart. The dbaas-crd chart is
# derived at build time by .github/scripts/build-crd-chart.sh, which copies the
# crd-*.yaml templates and inherits the version from the operator Chart.yaml.

FROM alpine:3.22 AS crd-builder
WORKDIR /src
COPY dbaas-operator/helm-templates/dbaas-operator/Chart.yaml operator/Chart.yaml
COPY dbaas-operator/helm-templates/dbaas-operator/templates/crd-*.yaml operator/templates/
COPY .github/scripts/build-crd-chart.sh build-crd-chart.sh
RUN sh build-crd-chart.sh operator /build/dbaas-crd

FROM scratch
COPY helm-templates/dbaas-aggregator /charts/dbaas-aggregator
COPY dbaas-operator/helm-templates/dbaas-operator /charts/dbaas-operator
COPY --from=crd-builder /build/dbaas-crd /charts/dbaas-crd
