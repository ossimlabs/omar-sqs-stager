{{- define "omar-sqs-stager.imagePullSecret" }}
{{- printf "{\"auths\": {\"%s\": {\"auth\": \"%s\"}}}" .Values.global.imagePullSecret.registry (printf "%s:%s" .Values.global.imagePullSecret.username .Values.global.imagePullSecret.password | b64enc) | b64enc }}
{{- end }}

{{/* Template for env vars */}}
{{- define "omar-sqs-stager.envVars" -}}
  {{- range $key, $value := .Values.envVars }}
  - name: {{ $key | quote }}
    value: {{ $value | quote }}
  {{- end }}
{{- end -}}

{{- define "omar-sqs-stager.volumeMounts.configmaps" -}}
{{- range $configmap := .Values.configmaps}}
- name: {{ $configmap.internalName | quote }}
  mountPath: {{ $configmap.mountPath | quote }}
  {{- if $configmap.subPath }}
  subPath: {{ $configmap.subPath | quote }}
  {{- end }}
{{- end -}}
{{- end -}}

{{- define "omar-sqs-stager.volumeMounts" -}}
{{- include "omar-sqs-stager.volumeMounts.configmaps" . -}}
{{- end -}}

{{- define "omar-sqs-stager.volumes.configmaps" -}}
{{- range $configmap := .Values.configmaps}}
- name: {{ $configmap.internalName | quote }}
  configMap:
    name: {{ $configmap.name | quote }}
{{- end -}}
{{- end -}}

{{- define "omar-sqs-stager.volumes" -}}
{{- include "omar-sqs-stager.volumes.configmaps" . -}}
{{- end -}}
