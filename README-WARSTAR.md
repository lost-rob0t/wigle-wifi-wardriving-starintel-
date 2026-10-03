# WarStar

WarStar is the StarIntel field fork of the WiGLE Android scanner. The upstream
scanner and its network API remain available under their original package and
protocol names for compatibility and attribution; the app name and field
console are branded WarStar.

## Field workflow

1. Scan offline or use the existing WiGLE services. StarIntel is optional. Open
   **WarStar Missions** to connect over HTTPS with an API key or username and
   password. The resulting token is kept only in process memory.
2. Sync canonical target documents routed to
   `star:v1:collector:wireless`. Targets are cached for offline viewing.
3. Tap **Tags** on a Wi-Fi or Bluetooth detail screen. Assign multiple icon and
   color tags, an optional target document ID, and an alert flag. Alert tags
   update the scanner's existing MAC alert matcher.
4. In Settings, switch **Show repeat devices in scan list** off to display
   devices first observed by this installation.
5. Import a WiGLE CSV or CSV.GZ export into the local database while offline, or
   send it to StarIntel after signing in. Upload the entire local observation
   database, including imported observations, in bounded batches; export the
   latest run or whole wireless database as Star Language 0.10.1 NDJSON
   documents. Upload needs a compatible StarIntel ingest server add-on. A cursor
   moves only after acknowledgment. Full upload restarts at the first observation;
   incremental upload keeps a separate cursor for each server and installation.
6. Generate a nearby low coverage GPX route. The algorithm uses a 7×7 grid of
   250 m cells around the current GPS fix, picks eight cells with the fewest
   locally stored observations, orders them by nearest neighbor, and shares
   waypoints with a map app. A map app should calculate road or path routing.

Exports use the generated Star Language 0.10.1 fields from
`specs/starintel/0.10.1/generated/schema.json` on the
`work/starintel-0.10.1-finalize` branch. The server's current schema lock
still points to 0.9.1, so server upload needs the matching 0.10.1 ingest
migration. See the server add-on issue.
