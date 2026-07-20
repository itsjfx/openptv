# OpenPTV

A third-party open-source alternative for the Public Transport Victoria (PTV) Android application with no Google Play services dependencies

OpenPTV is presented in [Material You](https://m3.material.io) style
<div align="center">
  
  <img width="19%" alt="light-favourites" src="https://github.com/user-attachments/assets/32e0ec48-6c6a-4769-a8fe-a20f03b6e467" />
  <img width="19%" alt="light-journey-planner" src="https://github.com/user-attachments/assets/664e5d86-3751-401c-923d-e70cd1fb2f96" />
  <img width="19%" alt="light-nearby-map" src="https://github.com/user-attachments/assets/e5fa853f-701a-47e3-b448-451682a4ad1a" />
  <img width="19%" alt="light-stop-detail" src="https://github.com/user-attachments/assets/5045f014-d08d-422e-8438-ca7a8bb33b44" />
  <img width="19%" alt="light-run-pattern" src="https://github.com/user-attachments/assets/dc07ea8e-1f88-487d-88be-f7df9aca35c0" />
  <p>Light mode</p>
  <br>
  <img width="19%" alt="dark-favourites" src="https://github.com/user-attachments/assets/28861d1e-062c-4c06-88a3-363f67923f72" />
  <img width="19%" alt="dark-journey-planner" src="https://github.com/user-attachments/assets/ce0275f7-3b31-4e4d-a377-0b48c237e194" />
  <img width="19%" alt="dark-nearby-map" src="https://github.com/user-attachments/assets/eca82cb8-3c35-4a6f-90f9-bfbccff1ad43" />
  <img width="19%" alt="dark-stop-detail" src="https://github.com/user-attachments/assets/6f30ade0-a025-4356-a166-45fd866c5221" />
  <img width="19%" alt="dark-run-pattern" src="https://github.com/user-attachments/assets/66b2952a-d4f5-479d-a510-4c7ac1af6737" />
  <p>Dark mode</p>
</div>

## Features

* [x] Basic map with stops displayed
* [x] Search for stops
* [x] View information for stops and routes; including scheduled departures and live ETA times
* [x] Favourites list, with scheduled and live ETA departure times
* [x] Information about departure (what stations it's been to, when, and where it's going)
* [x] Follow trips (pin to screen), and notification when nearing destination stop
* [x] Basic single-stop journey planner
* [ ] Basic multi-stop journey planner
* [ ] Multi-stop journey planner (with directions)
* [ ] Route path highlight on map

## Quick start

Download the latest APK from [GitHub Releases](https://github.com/itsjfx/openptv/releases/latest).

Verify the release is signed with the following key: `CE:3E:2D:12:4B:8C:5E:A9:39:45:D5:FA:36:5B:CD:E3:CC:39:02:BB:B4:22:50:A3:60:04:73:F8:05:6A:48:CE`

If you're using Obtanium, search for `itsjfx/openptv`.

You can also build your own APK, see [mobile#Build](./mobile/README.md#build).

## Why

The PTV app does not function without Google Play services. This causes issues for me on my Google Pixel running [GrapheneOS](https://grapheneos.org).

OpenPTV exists for only that reason. It's not aiming to be a competitor to the official PTV app, nor implement every feature - but it should implement *enough* for you to find your way around Victoria.

## Data / API / Privacy

OpenPTV has no tracking, ads, or analytics. We do not sell your information to anybody. I have no way of knowing how many people use this app.

OpenPTV's information is sourced from [PTV's Timetable API](https://www.vic.gov.au/public-transport-timetable-api). The information is licensed from Public Transport Victoria under a Creative Commons Attribution 4.0 International Licence.

On first launch, OpenPTV will ask how you'd like to connect to the PTV API. You can change your choice in the future in the settings page.

You can pick one of the following ways:

1. Use OpenPTV's proxy, hosted by the project maintainers (see [backend](./backend/README.md))
2. Provide your own proxy URL (see [backend](./backend/README.md) on how to host)
3. Make calls directly to PTV's API by providing your own API key

Sadly, requesting an API key from PTV can take varying amounts of time (days or weeks) - so the public OpenPTV proxy lets you use the app straight away.

We respect your privacy - HTTP access logs are collected for 24 hours for abuse tracking purposes only. Feel free to raise a GitHub issue for support if you notice issues with the proxy.

## I don't like OpenPTV, what can I use instead?

If you're using GrapheneOS, then you can install Sandboxed Google Play services and use the official PTV without any issues.

If you'd like to isolate Google Play services from your profile, then install Google Play services and PTV into your profile's private space. Private spaces work as a sandbox (similar to profiles). You can use PTV in there without issue.

I found opening and closing the private space annoying which is why I made OpenPTV, but I still have PTV in my private space.

## Thanks to

* [Public Transport Victoria](https://www.vic.gov.au/public-transport-timetable-api) - for providing the PTV API to the public
* [ReadYou](https://github.com/ReadYouApp/ReadYou) - which inspired Material You design in OpenPTV
* [Now in Android](https://github.com/android/nowinandroid) - helped base the patterns used in OpenPTV
