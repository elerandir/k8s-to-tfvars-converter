# k8s-to-tfvars-converter

[![CI](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/ci.yml)
[![CodeQL](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/scorecard.yml/badge.svg?branch=main)](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/scorecard.yml)
[![Secret scan](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/gitleaks.yml/badge.svg?branch=main)](https://github.com/elerandir/k8s-to-tfvars-converter/actions/workflows/gitleaks.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Built with Gradle](https://img.shields.io/badge/Built%20with-Gradle-02303A.svg?logo=gradle)](https://gradle.org)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

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
`ReplicaSet`, `ReplicationController`, `Job` — anything with a pod template at
`spec.template.spec`) it reads each container's env config:

| Source | Goes to | Behaviour |
| --- | --- | --- |
| `env[].value` | `env_vars` | Used verbatim. |
| `env[].valueFrom.configMapKeyRef` | `env_vars` | Resolved from the named `ConfigMap`. |
| `env[].valueFrom.secretKeyRef` | `secrets` | Records the Secret key; value not resolved. |
| `env[].valueFrom.fieldRef` / `resourceFieldRef` | — | Only known at pod runtime; reported as unresolved. |
| `envFrom[].configMapRef` / `secretRef` | — | Skipped (with a warning): a bulk import is not an explicit reference. |

When the same variable name appears in more than one container, the last one
wins (with a warning if the value differs). Output keys are sorted for stable,
diff-friendly files, and values are escaped as HCL string literals (including
Terraform `${...}`/`%{...}` sequences).

### Comment retention

Comments are captured from where each variable is defined:

- a comment above or trailing an `env[]` entry attaches to that variable;
- for a resolved `configMapKeyRef`/`secretKeyRef`, a comment on the `env[]` entry
  takes precedence, falling back to the comment on the referenced `ConfigMap`/`Secret`
  `data` (or `stringData`) entry.

## Build

Requires JDK 21 (the build targets Java 21). Uses the Gradle wrapper, so no
local Gradle install is needed.

The project uses [Lombok](https://projectlombok.org/) (compile-time only) to
generate constructors and utility-class scaffolding. Gradle handles it
automatically; for IDE editing, enable Lombok support (built in to IntelliJ;
install the plugin/agent for Eclipse).

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

## Security / supply chain

- **Wrapper integrity:** `gradle-wrapper.properties` pins the Gradle distribution
  by `distributionSha256Sum`, and CI runs `gradle/actions/wrapper-validation` to
  check `gradle-wrapper.jar` against known-good release checksums.
- **Dependency verification:** `gradle/verification-metadata.xml` records a SHA-256
  for every dependency artifact, so the build fails if a downloaded jar/pom does
  not match. Regenerate it when dependencies change (see below).
- **Dependency scanning:** `dependency-review` blocks pull requests that add
  vulnerable or disallowed-license dependencies; Dependabot keeps Gradle deps and
  GitHub Actions patched (`.github/dependabot.yml`).
- **Static analysis:** CodeQL scans the Java source on every push/PR and weekly;
  **OpenSSF Scorecard** scores the overall repository posture.
- **Secret scanning:** gitleaks scans pull requests and the full git history.
- **Runner hardening:** Harden-Runner audits CI runner egress; `CODEOWNERS`
  routes required reviews.
- **Workflow permissions** are scoped to least privilege (`contents: read`, with
  `security-events: write` only where SARIF upload needs it).

GitHub Actions are pinned to major version tags; for stricter hardening, pin them
to full commit SHAs (Dependabot will continue to bump SHA-pinned actions).

### Updating dependency verification metadata

When a dependency version changes (e.g. a Dependabot PR), regenerate the
checksums and commit the result:

```bash
./gradlew --write-verification-metadata sha256 build
```

## License

Copyright 2026 elerandir

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).
