## ADDED Requirements

### Requirement: Root network auto-stop suspends and resumes without leaking owned routes

Root module MUST receive a bounded set of at most five local-IP/CIDR/`gateway:` matching rules and evaluate them against the current physical network without location data. On a matched network, the supervisor MUST preserve the user's original running intent, suspend the core, and remove only the iptables/route entries owned by the current wanBox instance. On leaving the matching network, it MUST restore the prior running intent and recreate owned entries exactly once. A manual resume on the same network fingerprint MUST suppress another automatic suspension until the fingerprint changes; an explicit manual stop MUST NOT be undone by automatic resume.

#### Scenario: Network rule matches current gateway

- **GIVEN** a valid persisted `gateway:` rule matches the active physical network
- **WHEN** the Root supervisor observes the current fingerprint
- **THEN** it transitions to suspended and removes only wanBox-owned forwarding rules
- **AND** the reported state records the matched rule and original running intent without location identifiers

#### Scenario: Leave a matching network

- **GIVEN** the core was automatically suspended on a matching fingerprint
- **WHEN** a new physical network no longer matches any configured rule
- **THEN** the supervisor restores the prior running intent and recreates wanBox-owned routes once
- **AND** unrelated system routes and iptables rules remain unchanged

#### Scenario: Manual resume suppresses repeated suspension

- **GIVEN** the user manually resumes the core while the current fingerprint still matches a rule
- **WHEN** periodic network evaluation runs again on the same fingerprint
- **THEN** the core remains running and is not suspended again
- **AND** the suppression is cleared after the physical network fingerprint changes

#### Scenario: Cleanup failure remains visible and retryable

- **GIVEN** an owned route or iptables deletion fails during suspension
- **WHEN** the Root supervisor rolls back or retries cleanup
- **THEN** the returned state contains the failure and does not claim successful suspension
- **AND** retries are idempotent and do not delete resources owned by another process
