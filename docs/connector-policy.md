# Connector Policy

Connectors are the only modules that may access original sources.

## Connector Responsibilities

A connector may:

- request or inspect permission state
- scan for source references
- process a source reference transiently
- emit derived memory events
- report connector state
- support revoke/delete

A connector must not:

- persist raw content
- log raw content
- cache raw content
- export raw content
- transmit raw content
- expose raw content to store/core/LLM

## MVP Connectors

- Location Connector
- Photos Connector
- Calendar Connector
- Notification Connector
- App Usage Connector
- Local Files Connector

## Sensitivity

Notification and app usage connectors are highly sensitive.

They must default to OFF and require strong opt-in UX.
