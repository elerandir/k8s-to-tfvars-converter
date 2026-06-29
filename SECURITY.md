# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately rather than opening a public
issue. Use GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
("Report a vulnerability" under the repository's **Security** tab).

Please include:

- a description of the issue and its impact;
- steps to reproduce (a minimal manifest that triggers it, if applicable);
- the version or commit you tested.

We aim to acknowledge a report within a few days and to provide a remediation
timeline after triage.

## Scope and threat model

This tool reads Kubernetes manifests and writes a Terraform `.tfvars` file. The
main trust boundary is the **input manifests**, which may be untrusted.

- Manifests are parsed with SnakeYAML's node API (`composeAll`); the object
  `Constructor` is never invoked, so YAML type tags cannot instantiate arbitrary
  Java classes (the classic SnakeYAML deserialization vector does not apply).
- Values are written as escaped HCL string literals, including Terraform
  template sequences (`${...}` / `%{...}`), so manifest content cannot inject
  interpolation into the generated file.
- The tool performs no network calls and executes no external processes.

Note that **Secret values are never read or resolved** — only Secret keys are
recorded — so secret material does not flow into the output file.

## Supply chain

- The Gradle distribution is pinned by SHA-256 in `gradle-wrapper.properties`,
  and CI validates `gradle-wrapper.jar` against known-good release checksums.
- CodeQL, dependency-review, and Dependabot run in CI (see the README's
  "Security / supply chain" section).
