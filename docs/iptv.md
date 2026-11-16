# Built-in IPTV

The IPTV tile opens an Android TV-focused channel browser without changing the launcher's Java Home
screen or its default-home behavior.

## Sources and synchronization

- **M3U URL:** saves a remote playlist address and refreshes it on demand.
- **Local M3U:** imports a local file and stores the playlist text on the device.
- **Xtream:** stores a server address and credentials, then fetches live categories and streams.
- **Sync all:** refreshes remote playlists and Xtream channel data. The last successful channel cache remains
  available if a later refresh fails. Imported local playlists can be re-parsed with **Reload**.

There is no cloud account or cross-device sync. Source information and caches are encrypted with an
AES-GCM key held by Android Keystore. The key and data are local to the install; clearing app data or
uninstalling removes them.

## Android TV controls and playback

The screen is landscape, uses Compose focusable controls with a visible teal focus border, and supports
remote D-pad navigation. Back returns from playback to the channel list and from the list to Home.
Play/Pause, Media Play and Media Pause keys toggle playback.

Android playback uses `io.github.abdallahmehiz:mpv-android-lib`, not Nuvio's Apple-only MPVKit submodule.
The player uses MPV's GPU video output and `hwdec=auto-safe`, which selects hardware decoding only for
codecs supported safely by the device. The release targets `arm64-v8a` and `armeabi-v7a`; playback still
needs validation on the specific TV and provider stream.

## Network and privacy

The app makes network requests only when adding or syncing IPTV sources or playing a selected stream.
It does not add analytics, background refresh, or cross-device uploads. Source content, credentials and
cached stream URLs are encrypted at rest. HTTP is allowed for older IPTV providers, and the UI warns that
HTTP does not encrypt the connection; use HTTPS when the provider supports it.

## Licensing

The combined IPTV launcher is distributed under GPL-3.0-only. See `COPYING`, `LICENSE-MIT` for the
pre-existing launcher notice, and `THIRD_PARTY_NOTICES.md` for upstream attribution.
