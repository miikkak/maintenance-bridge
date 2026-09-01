# Security Policy

## Supported Versions

Only the latest released version is supported. Security fixes are not backported to older tags.

| Version  | Supported          |
| -------- | ------------------ |
| latest   | :white_check_mark: |
| < latest | :x:                |

## Reporting a Vulnerability

Please follow these steps if you discover a security vulnerability in this project:

### Do Not

- **Do not** open a public GitHub issue for security vulnerabilities
- **Do not** disclose the vulnerability publicly until it has been addressed

### Do

1. **Report privately** via [GitHub Security Advisories](https://github.com/miikkak/maintenance-bridge/security/advisories/new) <!-- markdownlint-disable-line MD013 -->
2. **Include in your report:**
   - Description of the vulnerability
   - Steps to reproduce the issue
   - Potential impact
   - Suggested fix (if you have one)

3. **Response timeline:**
   - You should receive an acknowledgment within 48 hours
   - We'll provide a detailed response within 7 days
   - We'll work with you to understand and fix the issue
   - We'll release a fix as soon as possible

## Scope

This plugin mirrors [Maintenance](https://github.com/kennytv/Maintenance)'s state to
`status.json`, and - unlike this project's sibling plugins - also **acts on external input**:
it polls `request.json` and, if it parses and passes validation, uses it to toggle maintenance
mode or set the active reason/ETA. It doesn't open any network listeners of its own and doesn't
read credentials. The trust boundary is local filesystem write access to the plugin's data
directory - whoever can write `request.json` there can toggle maintenance, by design (that's the
whole point: driving maintenance mode without going through RCON). Requests are size-capped
(64 KiB) before parsing and schema-validated before being applied; a malformed, oversized, or
otherwise rejected request is moved aside rather than acted on. If you find a way to toggle
maintenance, corrupt `status.json`, or otherwise affect the proxy through anything other than a
valid `request.json` drop by a local writer, that's exactly the kind of thing to report.

## Security Best Practices

When using this plugin in production:

- Always use a specific released version, not a locally built `SNAPSHOT` jar, in production
- Restrict filesystem write access to the plugin's data directory to whatever local principal
  legitimately owns your restart/maintenance tooling - anyone who can write `request.json` there
  can toggle maintenance mode
- Write `request.json` with correct permissions from the start (e.g. `install -m 644` or an
  equivalent atomic write+chmod), not a plain write followed by a separate `chmod` - see the
  README for why
- Keep the plugin updated - check releases periodically or watch the repository

## Security Scanning

This project uses automated security scanning:

- **Trivy** (filesystem scan against `gradle.lockfile`) for dependency vulnerability scanning,
  on a weekly schedule and on demand
- **Renovate** for automated dependency updates

## Other Automated Review

Every pull request also gets an AI code review. This is a general correctness/quality review,
not a vulnerability scanner - don't rely on it as a substitute for the security scanning above.

## Disclosure Policy

- Security issues are fixed in private before public disclosure
- After a fix is released, we publish a security advisory
- We credit reporters in the advisory (unless they prefer anonymity)

## Past Security Advisories

No security advisories have been published yet.

## Contact

For security-related questions or concerns, please use the reporting method above rather than
public channels.
