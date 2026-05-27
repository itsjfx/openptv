# OpenPTV

A third-party open-source alternative for the Public Transport Victoria (PTV) Android application with no Google Play service dependencies

OpenPTV is presented in [Material You](https://m3.material.io) style

## Features

* [x] Basic map with stops displayed
* [x] Search for stops
* [x] View information for stops and routes; including scheduled departures and live ETA times
* [x] Favourites list, with scheduled and live ETA departure times
* [ ] Information about departure (what stations its been to, when, where its going)
* [ ] Journey planner (may be tricky)
* [ ] Route path highlight on map

## Quick start

Download the latest pre-release APK from [GitHub Releases](https://github.com/itsjfx/openptv/releases/tag/preview) until I start signing and publishing release APKs.

Compile your own with `./gradlew :app:assembleDebug` on Java 21.

## Why

The PTV app does not function without Google Play Services. This causes issues for me on my Google Pixel running [GrapheneOS](https://grapheneos.org). As a result, this application will only ever support Android.

OpenPTV exists for only that reason. It's not aiming to be a competitor to the official PTV app, nor implement every feature - but it should implement *enough* for you to find your way around Victoria.

## Data / API / Privacy

OpenPTV's information is sourced from [PTV's Timetable API](https://www.vic.gov.au/public-transport-timetable-api). The information is licensed from Public Transport Victoria under a Creative Commons Attribution 4.0 International Licence.

OpenPTV on first start will ask how you want to call the PTV API. You can change your choice in the future in the settings page.

You can pick one the following ways:

1. Use OpenPTV's proxy, hosted by the project maintainers (see [backend](./backend))
2. Provide your own proxy URL (see [backend](./backend) on how to host)
3. Make calls directly to PTV's API by providing your own API key

OpenPTV's proxy exists so people can use OpenPTV straight away. Sadly, requesting an API key from PTV can take varying amounts of time (days or weeks).

We respect your privacy and do not sell any data or track you. We collect HTTP traffic logs for 24 hours for abuse tracking purposes only. You can freely use OpenPTV without your own key.

## I don't like OpenPTV. What can I use instead?

You can install Google Play services and use the official PTV without any issues.

If you'd like Play services not to leak onto other apps in your profile, then installing Play services into your profiles' private space works as a sandbox mechanism. You can then run PTV within the private space and use it without issue.

I found opening and closing the private space annoying which is why I made OpenPTV, but I still have PTV with Play services in my private space in case I need it.

## Thanks to

* [Public Transport Victoria](https://www.vic.gov.au/public-transport-timetable-api) for providing the PTV API to the public
* [ReadYou](https://github.com/ReadYouApp/ReadYou) - which inspired Material You design in OpenPTV
* [Now in Android](https://github.com/android/nowinandroid) - helped base the patterns used in OpenPTV
