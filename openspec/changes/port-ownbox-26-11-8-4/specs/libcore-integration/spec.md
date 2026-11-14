## ADDED Requirements

### Requirement: Root network auto-stop suspends and resumes without leaking core-owned routes

Root module MUST receive a bounded set of at most five local-IP/CIDR/`gateway:` matching rules and evaluate them against the current physical network without location data. On a matched network, the supervisor MUST preserve the user's running intent and request graceful termination only of its current core process; the core MUST close its own sing-box instance, TUN and `auto_route` resources before the module reports `suspended`. The module MUST NOT globally flush iptables/nftables or modify routes not owned by that core instance. On leaving the matching network, it MUST restore the prior running intent and start at most one replacement core. A manual resume on the same network fingerprint MUST suppress another automatic suspension until the fingerprint changes; an explicit manual stop MUST NOT be undone by automatic resume.

The optional `files/smart_auto_stop.json` snapshot file MUST contain the setting and rules, and MUST contain the validated application package identifier whenever the setting is enabled. The Root module MUST reject invalid package identifiers before applying the snapshot. On fixed lifecycle transitions, it MUST notify only that package through an explicit Android broadcast with the fixed action `<appPackage>.AUTO_STOP_STATE` and a single fixed phase extra (`suspended`, `connected`, `stopped`, `failed`, or `disabled`). The Root module MUST NOT put interface names, addresses, gateways, SSIDs, or rule text in the broadcast or logs, and MUST NOT invoke a shell with snapshot-derived text.

IPv4 and IPv6 default gateway discovery MUST read policy tables through the system `ip route show table all` interface using fixed arguments and short timeouts, retain `/proc` fallback, and continue safely when `ip` is unavailable. Only default routes with a valid `via` IP and `dev` interface may be parsed; gateways MUST remain associated with their interface so ignored-interface filtering still applies. Startup polling MUST accept `suspended` with the requested RunningRevision as successful confirmation of preserved running intent, but MUST NOT record `core_started` for it or accept a different revision or other unsuccessful state.

位于 active、non-ignored physical interface 的 IPv4/IPv6 link-local unicast 地址 MUST NOT 仅因 link-local scope 被过滤；这些地址 MUST 可由精确 IP 和 CIDR 规则匹配，该接口关联的 link-local 默认网关 MUST 可由 `gateway:` 规则匹配。

#### Scenario: Link-local unicast participates in physical network matching

- **GIVEN** active、non-ignored physical interface 上存在 IPv4/IPv6 link-local unicast 地址，且默认网关通过该接口关联
- **WHEN** Root supervisor 分别评估匹配该地址的精确 IP、CIDR 规则以及匹配该接口 link-local 默认网关的 `gateway:` 规则
- **THEN** 每种规则均可命中，不会仅因 link-local scope 过滤地址或网关
- **AND** ignored-interface 过滤仍然生效

#### Scenario: Network rule matches current gateway

- **GIVEN** a valid persisted `gateway:` rule matches the active physical network
- **WHEN** the Root supervisor observes the current fingerprint
- **THEN** it requests graceful termination of only its current core and transitions to `suspended` only after that process exits
- **AND** the reported state exposes a fixed auto-stop reason without revealing local IPs, gateway values, or the matched rule text

#### Scenario: Leave a matching network

- **GIVEN** the core was automatically suspended after its TUN/`auto_route` resources were released
- **WHEN** a new physical network no longer matches any configured rule
- **THEN** the supervisor restores the prior running intent and starts one core for the current revision
- **AND** unrelated system routes and iptables/nftables rules remain unchanged

#### Scenario: Manual resume suppresses repeated suspension

- **GIVEN** the user manually resumes the core while the current network fingerprint still matches a rule
- **WHEN** periodic network evaluation runs again on the same fingerprint
- **THEN** the core remains running and is not automatically suspended again
- **AND** the suppression is cleared after the physical network fingerprint changes

#### Scenario: Manual suppression survives configuration reload

- **GIVEN** manual resume suppressed automatic suspension on the current physical fingerprint
- **WHEN** config Apply replaces the supervisor
- **THEN** the replacement restores the suppression from a bounded private runtime marker and remains running on that fingerprint
- **AND** observing a different physical fingerprint clears the marker through the same decision used by active and suspended monitoring

#### Scenario: Abnormal auto-stop exit fails closed

- **GIVEN** automatic suspension requested graceful core cleanup
- **WHEN** the core exits abnormally or its sing-box instance Close fails
- **THEN** the supervisor reports failed with cleanup_required, never suspended, and does not automatically start another core
- **AND** the standalone core returns a nonzero result with fixed safe output, retaining close diagnostics only at the controlled error boundary
- **AND** explicit stop remains safe to retry

#### Scenario: Core cleanup timeout remains visible and retryable

- **GIVEN** the core does not exit within the graceful shutdown deadline
- **WHEN** the Root supervisor handles the cleanup timeout
- **THEN** the returned state contains the failure and does not claim successful suspension or start a duplicate core
- **AND** the caller can retry an explicit stop without affecting other processes or routes

#### Scenario: Root publishes only a fixed lifecycle phase to the configured app

- **GIVEN** the snapshot contains a valid app package identifier and auto-stop changes phase
- **WHEN** the Root module reports `suspended`, `connected`, `stopped`, `failed`, or `disabled`
- **THEN** it sends an explicit broadcast only to the configured app with action `<appPackage>.AUTO_STOP_STATE` and the corresponding fixed phase
- **AND** the broadcast contains no physical network identifiers, matching rule text, or core error output



#### Scenario: Startup immediately matches an auto-stop rule

- **GIVEN** the requested revision reports `suspended` after confirmed core cleanup
- **WHEN** startup polling observes that same RunningRevision
- **THEN** startup succeeds with the user's running intent preserved
- **AND** no `core_started` event is recorded and suspension from a different revision is not accepted

Broadcast delivery MUST be independent of the supervisor lifecycle: enqueueing MUST NOT wait for Android `am broadcast`. A detached dispatcher MUST serialize/coalesce pending phases across supervisor replacements, bound every command with a short timeout, and prevent a stale `suspended` notification from being delivered after a newer `connected`, `stopped`, `failed`, or `disabled` phase. Arguments MUST be passed as argv, never shell-interpolated.

Enabled `files/smart_auto_stop.json` MUST include `userId`, the current Android user/profile identifier, as an integer in the supported Android UID range (0–21474), as well as `appPackage`. Missing, null, fractional, negative, or out-of-range identities MUST be rejected before Apply; no enabled snapshot may silently default to user 0. Disabled/legacy snapshots MAY omit user identity; without one the module MUST NOT dispatch notifications. `am broadcast --user` MUST use the validated identifier as a separate argv value.

#### Scenario: Apply preserves the cleanup failure barrier

- **GIVEN** a newly applied generation fails startup with `cleanup_required`
- **WHEN** Apply considers automatic rollback
- **THEN** it preserves and returns `cleanup_required` without automatically stopping away the failure or restarting the old generation
- **AND** subsequent Start remains blocked until explicit Stop recovery

#### Scenario: Slow lifecycle delivery does not block networking

- **GIVEN** Android broadcast delivery stalls during suspension
- **WHEN** the supervisor transitions to connected or stopped
- **THEN** the transition proceeds without waiting for delivery and the stalled command times out
- **AND** serialized/coalesced delivery leaves the latest phase, never a stale suspended notification

#### Scenario: Notifications target a work profile

- **GIVEN** an enabled snapshot contains a validated nonzero Android `userId`
- **WHEN** a lifecycle notification is dispatched
- **THEN** the explicit broadcast targets that user/profile and application package only
- **AND** an enabled snapshot without user identity is rejected while disabled legacy snapshots remain compatible
