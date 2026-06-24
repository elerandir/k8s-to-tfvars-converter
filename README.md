# k8s-to-tfvars-converter

A small Java CLI that extracts container environment variables from Kubernetes
manifests and emits a Terraform [`.tfvars`](https://developer.hashicorp.com/terraform/language/values/variables#variable-definitions-tfvars-files)
file with two object maps:

```hcl
env_vars = {
  "DATABASE_URL"  = "postgres://db:5432/app" # primary database
  # comma-separated feature toggles
  "FEATURE_FLAGS" = "a,b,c"
  # how chatty the logs are
  "LOG_LEVEL"     = "info"
}

secrets = {
  # token for the upstream API
  "API_TOKEN" = "api_token"
}
```

- **`env_vars`** holds literal and ConfigMap-backed variables. ConfigMap
  references are resolved to their actual values. Variable names are kept
  verbatim (original case and separators).
- **`secrets`** holds Secret-backed variables. Their values are **not** resolved;
  each entry maps the variable name to the **key within the Secret**, which
  another part of the system uses to resolve the real value.
- Both the variable names (keys) and their values are quoted strings.
- Comments near a variable in the manifest are carried over as Terraform
  comments next to the matching entry.

Only variables that the workload **explicitly references** are emitted. A
ConfigMap or Secret entry that is not referenced by an `env` entry is left out,
and bulk `envFrom` imports (which would pull in an entire ConfigMap/Secret) are
skipped with a warning.

## What it handles

For every workload found in the input (`Deployment`, `StatefulSet`, `DaemonSet`,
`ReplicaSet`, `Job`, `CronJob`, `Pod`, ...) it reads each container's env config:

| Source | Goes to | Behaviour |
| --- | --- | --- |
| `env[].value` | `env_vars` | Used verbatim. |
| `env[].valueFrom.configMapKeyRef` | `env_vars` | Resolved from the named `ConfigMap`. |
| `env[].valueFrom.secretKeyRef` | `secrets` | Records the Secret key; value not resolved. |
| `env[].valueFrom.fieldRef` / `resourceFieldRef` | — | Only known at pod runtime; reported as unresolved. |
| `envFrom[].configMapRef` / `secretRef` | — | Skipped (with a warning): a bulk import is not an explicit reference. |

`env` entries override `envFrom` entries with the same name, matching Kubernetes
semantics. Output keys are sorted for stable, diff-friendly files, and values are
escaped as HCL string literals (including Terraform `${...}`/`%{...}` sequences).

### Comment retention

Comments are captured from where each variable is defined:

- a comment above or trailing an `env[]` entry attaches to that variable;
- for a resolved `configMapKeyRef`/`secretKeyRef`, a comment on the `env[]` entry
  takes precedence, falling back to the comment on the referenced `ConfigMap`/`Secret`
  `data` (or `stringData`) entry.

## Build

Requires a JDK (17+; built and tested with Java 21). Uses the Gradle wrapper, so
no local Gradle install is needed.

```bash
./gradlew build          # compile + test
./gradlew installDist    # produce a runnable distribution under build/install/
```

## Usage

```bash
# Print tfvars to stdout (pass the workload and its ConfigMaps/Secrets;
# order does not matter)
./build/install/k8s-to-tfvars/bin/k8s-to-tfvars deployment.yaml configmap.yaml secret.yaml

# Read a whole directory of manifests and write to a file
./build/install/k8s-to-tfvars/bin/k8s-to-tfvars ./manifests -o app.tfvars
```

### Options

| Option | Description |
| --- | --- |
| `-o, --output <file>` | Write to a file instead of stdout. |
| `-c, --container <name>` | Only extract from the container with this name. |
| `--include-init-containers` | Also read `initContainers`. |
| `--include-unresolved` | Emit unresolved vars (e.g. `fieldRef`) as commented placeholders instead of dropping them. |
| `--fail-on-unresolved` | Exit non-zero if any env var cannot be resolved. |
| `--no-header` | Omit the generated-file header comment. |
| `-h, --help`, `-V, --version` | Help / version. |

See `src/test/resources/sample-app.yaml` for a complete example input.
