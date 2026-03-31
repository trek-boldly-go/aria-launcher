# Security Policy

## Supported Versions

| Branch | Supported |
|--------|-----------|
| `aria-dev` | Yes |
| All others | No |

Only the current `aria-dev` branch receives security fixes. There are no stable release branches yet.

## Security Model

ARIA is designed with privacy and security as core principles:

- **All user data stays on-device.** Usage patterns, context signals, predictions, rules, and skill results are stored in a local Room database. Nothing is synced to a cloud service.
- **LLM calls are opt-in.** AI features only work when the user explicitly configures an LLM provider. No data is sent to any remote service by default.
- **No telemetry.** ARIA does not collect analytics, crash reports, or usage metrics.
- **Skill URL fetching has safety checks.** The `fetch_url` tool blocks requests to `localhost`, private IP ranges (`10.*`, `172.16-31.*`, `192.168.*`), `file://` URIs, and other internal addresses to prevent SSRF attacks.
- **Skill installation is user-initiated.** Skills can only be installed through explicit user action (chat command, URL import, or manual file placement).

## Reporting Security Issues

To report a security vulnerability, please open a [security advisory](https://github.com/trek-boldly-go/aria-launcher/security/advisories/new) on this repository.

Do **not** file a public issue for security vulnerabilities. Use the private security advisory process so the issue can be fixed before disclosure.
