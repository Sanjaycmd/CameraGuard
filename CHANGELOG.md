# CameraGuard Changelog

## [v1.0.0] - 2026-09-25

### Added
- Camera availability monitoring.
- Camera event history.
- Contextual application inference.
- Camera permission inference.
- UsageStats-based camera detection fallback.
- CameraManager/UsageStats deduplication.
- Camera session ownership tracking.
- History copy-all functionality.
- Individual event copy functionality.
- Responsive History UI category indicators.
- Automated unit tests for monitoring and History functionality.

### Fixed
- Startup camera availability callbacks being incorrectly recorded as events.
- Third-party camera permission being incorrectly reported as denied when it could not be verified.
- Duplicate camera events from overlapping detection paths.
- Camera closure being attributed to an unrelated foreground application.
- History category indicator layout on different screen resolutions.

### Validation
- Stock Camera detection tested on physical Vivo device.
- WhatsApp camera access tested.
- Google Lens camera access tested.
- Chrome normal usage tested with zero false events.
- Google normal usage tested with zero false events.
- Repeated stock Camera sessions tested.
- Unit tests passed.
- Debug APK build passed.
