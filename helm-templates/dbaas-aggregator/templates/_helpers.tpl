{{/*
Basic DBaaS resource labels shared by the lighter resources (secrets, service accounts, hook jobs,
dashboard). Value-driven labels are rendered only when the corresponding value is set.
*/}}
{{- define "dbaas.basicLabels" -}}
app.kubernetes.io/name: '{{ .Values.SERVICE_NAME }}'
app.kubernetes.io/part-of: '{{ .Values.APPLICATION_NAME }}'
{{- if .Values.MANAGED_BY }}
app.kubernetes.io/managed-by: '{{ .Values.MANAGED_BY }}'
{{- end }}
deployment.netcracker.com/sessionId: '{{ .Values.DEPLOYMENT_SESSION_ID | default "unimplemented" }}'
{{- end -}}

{{/*
Full DBaaS resource label set used by the Deployment (metadata and pod template).
`component` and `technology` describe the aggregator service itself.
Value-driven labels are rendered only when the corresponding value is set.
*/}}
{{- define "dbaas.resourceLabels" -}}
app.kubernetes.io/name: '{{ .Values.SERVICE_NAME }}'
app.kubernetes.io/part-of: '{{ .Values.APPLICATION_NAME }}'
{{- if .Values.MANAGED_BY }}
app.kubernetes.io/managed-by: '{{ .Values.MANAGED_BY }}'
{{- end }}
app.kubernetes.io/instance: '{{ cat (coalesce .Values.DEPLOYMENT_RESOURCE_NAME .Values.SERVICE_NAME) "-" .Values.NAMESPACE | nospace | trunc 63 | trimSuffix "-" }}'
{{- if .Values.ARTIFACT_DESCRIPTOR_VERSION }}
app.kubernetes.io/version: '{{ .Values.ARTIFACT_DESCRIPTOR_VERSION }}'
{{- end }}
app.kubernetes.io/component: 'backend'
app.kubernetes.io/technology: 'java-quarkus'
netcracker.cloud/disaster-recovery.manageable: 'true'
deployment.netcracker.com/sessionId: '{{ .Values.DEPLOYMENT_SESSION_ID | default "unimplemented" }}'
{{- end -}}

{{/*
Resolve the hook (validation) image. The deployment platform can pass the full image name through the
`deployDescriptor` map (entity name `validation-dbaas-image`); otherwise `DECLARATIVE_HOOK_IMAGE` is used.
*/}}
{{- define "dbaas.hookImage" -}}
{{- $image := .Values.DECLARATIVE_HOOK_IMAGE -}}
{{- if and .Values.deployDescriptor (index .Values.deployDescriptor "validation-dbaas-image") -}}
{{- $image = index (index .Values.deployDescriptor "validation-dbaas-image") "full_image_name" | default $image -}}
{{- end -}}
{{- $image -}}
{{- end -}}

{{/* Convert a CPU quantity ("500m", "1", "0.5") to integer millicores for HPA calculations. */}}
{{- define "to_millicores" -}}
  {{- $value := toString . -}}
  {{- if hasSuffix "m" $value -}}
    {{ trimSuffix "m" $value }}
  {{- else -}}
    {{ mulf $value 1000 }}
  {{- end -}}
{{- end -}}
