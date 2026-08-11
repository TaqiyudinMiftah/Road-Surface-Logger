# v0.3 Implementation Status

This branch completes the UI integration for features whose acquisition/runtime support is already present in `main` after PR #1:

- 3D device-orientation visualization driven by the rotation-vector stream
- visible azimuth, pitch, and roll values
- live/current-session route map button
- recorded-session `View Route` action
- Maps SDK local API-key setup documentation

The map intentionally visualizes raw GNSS points and does not perform map matching or snap-to-road processing.
