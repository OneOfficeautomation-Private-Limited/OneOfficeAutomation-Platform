{{- define "ooa.name" -}}{{ .Chart.Name | trunc 63 | trimSuffix "-" }}{{- end -}}

{{- define "ooa.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}{{ .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else -}}{{ printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- end -}}

{{- define "ooa.labels" -}}
app.kubernetes.io/name: {{ include "ooa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "ooa.selector" -}}
app.kubernetes.io/name: {{ include "ooa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: command-center
{{- end -}}

{{/* Checks that stop a set-up that cannot work. */}}
{{- define "ooa.checks" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.database.url) -}}
{{- fail "replicaCount > 1 needs database.url: the built-in evaluation database cannot be shared" -}}
{{- end -}}
{{- if and .Values.database.url (not .Values.database.existingSecret) -}}
{{- fail "database.url needs database.existingSecret holding the database password" -}}
{{- end -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.masterKey.existingSecret) -}}
{{- fail "replicaCount > 1 needs masterKey.existingSecret: every node must use the same vault key" -}}
{{- end -}}
{{- end -}}
