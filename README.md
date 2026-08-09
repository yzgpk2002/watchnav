# WatchNav

Clean-room Wear OS offline-navigation foundation.

## Implemented vertical slice

- API 30 minimum and API 35 target, with API 36 compile SDK.
- Pure Kotlin GPX parser with DTD/external-entity processing disabled.
- Storage Access Framework GPX import with parsing and course preparation off the UI thread.
- HTTPS GPX download with bounded redirects, timeouts, a 16 MB limit, parse-before-save,
  and an internal `courses/latest.gpx` copy for offline reuse.
- Multi-segment course model, cumulative distance, spatial grid index, course matching,
  stable progress, generated turn cues, and hysteresis-based off-course detection.
- Embedded MIT-licensed BRouter engine with manually importable `rd5` regions,
  elevation-aware offline A-B routing, route-to-course-start, and automatic rejoin routing.
- Signed regional package envelopes with Ed25519 verification, resumable HTTPS artifact
  downloads, declared-size/SHA-256 validation, storage checks, update state, and rollback.
- Foreground navigation service with real GNSS updates, persisted session snapshots,
  process-restart progress restoration, and Wear OS Ongoing Activity.
- Adaptive maneuver vibration stages, off-course/rejoin alerts, and completion detection.
- Elevation-profile analysis with remaining ascent, automatic climb segmentation,
  a live profile graph, smoothed grade, ClimbPro-style current/next-climb guidance,
  and staged climb vibration alerts.
- Persistent sampled activity recording, GPX activity export, course reversal, and
  TracBack navigation over the recorded outbound track; completed activities are archived
  with GPX and FIT exports, laps, Health Services heart rate/distance/calories, and auto-pause.
- Pressure-altimeter calibration, rotation-vector compass fallback, vertical speed,
  saved waypoints, a persistent course library, and accurate/balanced/expedition GNSS modes.
- Mapsforge 0.25 offline `.map` import and local rendering with course, completed-track,
  accuracy, and live-position overlays; a basemap-free course view remains as fallback.
- Outdoor render-theme support for major/medium/minor contour lines and elevation labels
  when the selected Mapsforge package contains contour data.
- Map-follow toggle and Wear OS rotary-input zoom for crown/bezel operation.
- Round-screen Chinese/English navigation UI, course distance/ascent summary, optional
  always-on mode, and physical Back-key touch lock.

## Build

```powershell
.\gradlew.bat test assembleDebug
```

GNSS fixes use Android `LocationManager`; Health Services supplies the exercise lifecycle
and fitness metrics, while available pressure and rotation sensors improve altitude and
heading. Production catalog signing, POI indexes, and field power profiling remain in
`docs/NEXT_SLICE.md`.
