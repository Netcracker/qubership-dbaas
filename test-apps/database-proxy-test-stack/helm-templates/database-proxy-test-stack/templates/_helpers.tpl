{{/*
Cluster address of the TCP proxy. DBaaS Proxy rewrites database connection properties to this host,
so it must resolve from the application's namespace, not only from the proxy's own.
*/}}
{{- define "database-proxy-test-stack.tcpProxyHost" -}}
{{- if .Values.TCP_PROXY_HOST -}}
{{- .Values.TCP_PROXY_HOST -}}
{{- else -}}
{{- printf "%s.%s" .Values.TCP_PROXY_SERVICE_NAME .Values.NAMESPACE -}}
{{- end -}}
{{- end -}}

{{/*
TCP_PROXY_MAPPINGS for DBaaS Proxy, generated from DATABASE_MAPPINGS:
  <listenPort>/<name>@<targetHost>:<targetPort>,...
Generating it from the same list that renders haproxy.cfg keeps the rewrite target and the listening
frontend in step.
*/}}
{{- define "database-proxy-test-stack.tcpProxyMappings" -}}
{{- $mappings := list -}}
{{- range .Values.DATABASE_MAPPINGS -}}
{{- $mappings = append $mappings (printf "%d/%s@%s:%d" (int .listenPort) .name .targetHost (int .targetPort)) -}}
{{- end -}}
{{- join "," $mappings -}}
{{- end -}}
