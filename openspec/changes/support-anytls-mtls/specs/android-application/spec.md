## ADDED Requirements

### Requirement: AnyTLS private key is not shown in settings summaries

Android MUST provide localized multi-line client certificate and private-key inputs in AnyTLS settings. The private-key value MUST NOT be displayed in its preference summary; a fixed safety summary MAY explain that the material is stored locally and excluded from share links.

Portable backups and sing-box JSON configuration exports MAY contain the AnyTLS client certificate and private key. Documentation MUST identify such exports as sensitive and MUST NOT describe them as redacted.

#### Scenario: Edit the client private key

- **GIVEN** a user enters a client private key in AnyTLS settings
- **WHEN** the settings page shows the preference summary
- **THEN** the summary does not reveal any part of the private-key value
- **AND** the entry remains editable as multi-line PEM text

#### Scenario: Export a backup with mTLS configured

- **GIVEN** an AnyTLS profile contains a client certificate and private key
- **WHEN** a portable backup or sing-box JSON configuration export is created
- **THEN** the exported file may contain those TLS materials and is treated as sensitive data
- **AND** only share-link formats are described as excluding the mTLS identity
