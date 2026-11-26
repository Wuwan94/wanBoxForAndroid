## ADDED Requirements

### Requirement: AnyTLS mTLS identity is stored locally and applied to outbound TLS

Android MUST allow an AnyTLS profile to persist client certificate and private-key PEM text locally. Non-empty values MUST be mapped to sing-box outbound TLS `client_certificate` and `client_key` PEM line arrays; blank values MUST be omitted. Older AnyTLS profile versions MUST decode successfully with both new fields empty.

#### Scenario: Persist and apply a client identity

- **GIVEN** a user saves an AnyTLS profile containing a PEM client certificate and private key
- **WHEN** the profile is loaded and an outbound is generated
- **THEN** the local profile retains both values and outbound TLS contains their non-empty line arrays
- **AND** a profile with no client identity omits both TLS fields

#### Scenario: Decode a profile created before mTLS support

- **GIVEN** an AnyTLS profile encoded with the prior version
- **WHEN** the application decodes it
- **THEN** the existing profile data remains readable and the new client identity fields are empty

### Requirement: AnyTLS share links exclude client identity material

Both standard `anytls://` and generic `sn://` AnyTLS share links MUST exclude the client's certificate and private key. Generic sharing MUST sanitize a clone and MUST NOT mutate the saved profile. Other supported server and profile fields MUST remain shareable.

#### Scenario: Share AnyTLS without its mTLS identity

- **GIVEN** an AnyTLS profile contains a client certificate and private key
- **WHEN** the user exports an `anytls://` or `sn://` share link and imports it
- **THEN** the imported profile has empty client identity fields and retains its supported server/profile fields
- **AND** the original local profile remains unchanged
