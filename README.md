# Feather

A small, private Android browser built on the system WebView. No AndroidX, no analytics, no accounts.

## What it does
- **Ad & tracker blocking** - ~42k blocked domains (anudeepND list, refreshed weekly by CI), matched against every
  sub-resource request, plus a small cosmetic filter that hides leftover ad frames. Per-site off switch (shield button).
- **HTTPS-only mode** - `http://` links are upgraded; if a site has no HTTPS you get a warning before loading plain HTTP.
- **Strips tracking parameters** (`utm_*`, `fbclid`, `gclid`, ...) from links you open.
- **Third-party cookies blocked**, mixed content blocked, autoplay blocked, file/content access off.
- **Camera, mic and location always denied.** Popups can't open on their own; unknown app-link schemes (`intent:` etc.) are blocked.
- **Reduced fingerprint** - generic Chrome user-agent (no device model, no `wv` marker), `Sec-GPC`/`DNT` sent.
- **Safe Browsing** stays on (Google's WebView malware/phishing check).
- No history is stored. Optional "clear all data when Feather closes".
- Tabs, bookmarks, desktop-site mode, fullscreen video, downloads (asks first), search engine choice
  (DuckDuckGo, Brave, Startpage, Google), can be set as default browser.

## Build
Push to GitHub. The **Build APK** workflow produces `Feather.apk` (workflow artifact and the `latest` release).
Locally: open in Android Studio, or `gradle :app:assembleRelease` (JDK 17, Android SDK 35).

The APK is signed with `keystore/sideload.jks` (password `feather-sideload`), committed so every build can update the
last. For Google Play, replace it with your own upload key.

## Limits
- It's a WebView browser: no extensions, and YouTube's own in-stream ads aren't blocked (they come from youtube.com itself).
- Host-based blocking can't remove ads served from the same domain as the content.
