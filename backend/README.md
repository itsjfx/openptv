# OpenPTV Backend

A dumb Go service which proxies [the PTV API](https://www.vic.gov.au/public-transport-timetable-api).

Code is available for transparency and so users can host their own proxy if desired.

## Why

Requests to PTV must be signed with an API key. This handles signing outgoing requests so our API key is not vendored as part of the Android application.

## Development

Set `OPENPTV_PTV_DEV_ID` and `OPENPTV_PTV_KEY` with your key from PTV. See: <https://www.vic.gov.au/public-transport-timetable-api>

```bash
make build
make test
make run
```
