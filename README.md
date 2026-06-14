# OpenPTV

A third-party open-source alternative for the Public Transport Victoria (PTV) Android application with no Google Play services dependencies

OpenPTV is presented in [Material You](https://m3.material.io) style
<div align="center">
  <img src="https://github.com/user-attachments/assets/9cfe6244-0f70-45d6-9606-0c431cac3608" width="32.0%" alt="light-favourites" />
  <img src="https://github.com/user-attachments/assets/04939830-76c3-4ff6-8548-6cb823117177" width="32.0%" alt="light-nearby-map" />
  <img src="https://github.com/user-attachments/assets/8bbf13c6-5301-4ff1-ae76-e5fe960c0591" width="32.0%" alt="light-stop-detail" />
  <p>Light mode</p>
  <br>
  <img src="https://github.com/user-attachments/assets/e876095d-b6c7-4837-9b5c-b253571eaba7" width="32.0%" alt="dark-favourites" />
  <img src="https://github.com/user-attachments/assets/63037a21-bc59-4695-b6a1-eed159df479c" width="32.0%" alt="dark-nearby-map" />
  <img src="https://github.com/user-attachments/assets/99e0953e-0ee9-475a-9e44-df202f6634f3" width="32.0%" alt="dark-stop-detail" />
  <p>Dark mode</p>
</div>

## Features

* [x] Basic map with stops displayed
* [x] Search for stops
* [x] View information for stops and routes; including scheduled departures and live ETA times
* [x] Favourites list, with scheduled and live ETA departure times
* [x] Information about departure (what stations it's been to, when, and where it's going)
* [ ] Journey planner (may be tricky)
* [ ] Route path highlight on map

## Quick start

Download the latest pre-release APK from [GitHub Releases](https://github.com/itsjfx/openptv/releases/tag/preview) until I start signing and publishing release APKs.

You can also build your own APK, see [mobile#Build](./mobile/README.md#build).

## Why

The PTV app does not function without Google Play services. This causes issues for me on my Google Pixel running [GrapheneOS](https://grapheneos.org).

OpenPTV exists for only that reason. It's not aiming to be a competitor to the official PTV app, nor implement every feature - but it should implement *enough* for you to find your way around Victoria.

## Data / API / Privacy

OpenPTV's information is sourced from [PTV's Timetable API](https://www.vic.gov.au/public-transport-timetable-api). The information is licensed from Public Transport Victoria under a Creative Commons Attribution 4.0 International Licence.

On first launch, OpenPTV will ask how you'd like to connect to the PTV API. You can change your choice in the future in the settings page.

You can pick one of the following ways:

1. Use OpenPTV's proxy, hosted by the project maintainers (see [backend](./backend/README.md))
2. Provide your own proxy URL (see [backend](./backend/README.md) on how to host)
3. Make calls directly to PTV's API by providing your own API key

Sadly, requesting an API key from PTV can take varying amounts of time (days or weeks) - so the public OpenPTV proxy lets you use the app straight away.

We respect your privacy and do not sell any data or track you. We collect HTTP access logs for 24 hours for abuse tracking purposes only.

Feel free to rely on the proxy and raise a GitHub issue for support.

## I don't like OpenPTV, what can I use instead?

If you're using GrapheneOS, then you can install Sandboxed Google Play services and use the official PTV without any issues.

If you'd like to isolate Google Play services from your profile, then install Google Play services and PTV into your profile's private space. Private spaces work as a sandbox (similar to profiles). You can use PTV in there without issue.

I found opening and closing the private space annoying which is why I made OpenPTV, but I still have PTV in my private space.

## Thanks to

* [Public Transport Victoria](https://www.vic.gov.au/public-transport-timetable-api) - for providing the PTV API to the public
* [ReadYou](https://github.com/ReadYouApp/ReadYou) - which inspired Material You design in OpenPTV
* [Now in Android](https://github.com/android/nowinandroid) - helped base the patterns used in OpenPTV
