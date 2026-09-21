# Software provenance

This register records where reused material came from and why it may be used here. An entry must be approved before source code or binary assets are imported.

| Component | Source | Source revision | Contributors | Permission | Status |
|---|---|---|---|---|---|
| Project architecture | EastWing project planning | Not applicable | Fredrik Gustafsson | Original project work | Approved |
| Initial platform contracts and EastWing app shell | This repository | Initial implementation | Fredrik Gustafsson with ChatGPT assistance | Original project work | Approved |
| Gradle wrapper | Local Fredrik-authored Qdrone project; generated Gradle tooling | Gradle 8.13 wrapper | Gradle project | Standard generated build tooling | Approved |
| Qulinda Qhub components | `eprotection/qhub` | `7ea5ad806ec2ec9a10aaedba6b47f65b9c2093fe` | To be recorded per component | Pending written interim permission | Not imported |
| Qdrone components | Local Qdrone project | To be recorded | Fredrik Gustafsson | To be confirmed | Not imported |
| Qauto components | Local Qauto project | To be recorded | Fredrik Gustafsson | To be confirmed | Not imported |
| APV model and labels | Qcamera or Qhub camera assets | To be recorded | To be confirmed | Model and data rights to be confirmed | Not imported |

## Import checklist

- Identify the exact source path and revision.
- Record the principal contributors.
- Confirm permission and any field-of-use restriction.
- Check third-party dependencies and binary assets.
- Remove credentials, endpoints, signing configuration, and organization-specific branding.
- Import the component in a dedicated commit.
