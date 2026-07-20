# Security policy

## Supported versions

Security fixes are applied to the latest released minor version. This project is currently in its
initial release cycle; no older release line is supported yet.

## Reporting a vulnerability

Email `support@atlasarc.io` with a description, affected version, reproduction details, and
the impact you believe is possible. Please do not open a public GitHub issue until a fix or
coordinated disclosure is available. Seamra Works will acknowledge a useful report and coordinate
next steps by email.

AtlasArc CI reads repository files and build artifacts but does not invoke build tools or Node
tooling. Treat dependency evidence as untrusted input when integrating the library into a service.
