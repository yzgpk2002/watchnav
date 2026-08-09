# Next implementation slice

1. Provision a production Ed25519 catalog key and publish contour Mapsforge + BRouter
   `rd5` regional package manifests from a reproducible map-build pipeline.
2. Add a compact searchable POI index artifact and map-tap destination selection.
3. Persist prepared course spatial/elevation indexes to reduce restart time for very
   large tracks, and add FIT session/lap summaries beyond the current record stream.
4. Add heading-up map rotation and automatic follow timeout after manual panning.
5. Field-profile GNSS accuracy, Health Services availability, vibration timing, memory,
   temperature, and battery drain on target Samsung watches for 6–12 hour activities.
6. Validate recovery from process death, full storage, corrupt packages, low battery,
   unavailable sensors, wet-screen input, and weak/blocked GNSS before production release.
