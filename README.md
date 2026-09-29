# TripMapAndroid

TripMapAndroid is an Android application for trip/route mapping, GPS tracking, chainage, intersections, trip history, and route export.

## Version

**v1.4**

This repository is based on the original `TripMapAndroid-v1_4-dxf-share-history` project. Later experimental v1.6/v1.7/v1.8/v1.9/v2.x changes are intentionally not included.

## Main v1.4 capabilities

- Google Maps and real-time GPS
- Route/trip distance and chainage
- Thai voice navigation at 100 m intervals
- Trip history stored with Room
- OpenStreetMap intersection lookup
- Trip/history editing
- Export options including PNG/PDF/KMZ/DXF
- Export by selected kilometre range and interval options
- Portable saved-trip/history data for transfer between devices
- Date-based export filenames

## Requirements

- Android Studio
- Android SDK / build tools required by the project
- A Google Maps API key configured for your own development environment

## Google Maps API key

Do **not** commit a real API key or other credentials to this public repository.

Configure your own Google Maps key using the mechanism already provided by the project, and keep local/secret configuration out of Git. See `.gitignore` for files that should remain local.

## Build

1. Open the project in Android Studio.
2. Allow Gradle synchronization to complete.
3. Configure your own Maps API key.
4. Build and run the `app` module on an Android device/emulator.

## GitHub

Suggested repository name:

`TripMapAndroid`

Recommended visibility:

**Public**

Before pushing, review the repository for credentials, signing files, and private configuration.
