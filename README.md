# BookShelf

An Android ebook reader for the Audiobookshelf library on **Athena**. Covers, metadata, books and reading progress all come from your server; the app updates itself from this repo's releases.

## Install

Download the latest `BookShelf-v1.0.N.apk` from [Releases](https://github.com/Jondude1/BookShelf/releases/latest) once. After that the app checks for new versions on its own and shows an **Update** banner.

## First run

1. **Home address** — Audiobookshelf on your LAN, e.g. `http://192.168.1.20:13378`
2. **Away address** (optional) — Athena over Tailscale, e.g. `http://athena:13378`. BookShelf pings both and uses whichever answers, so it works at home and away without changing anything.
3. Sign in with your Audiobookshelf username/password, or paste an API key (Audiobookshelf → Settings → API Keys).

Only library items that have an ebook file are shown. If you have more than one book library, pick one in Settings, or keep "All ebook libraries".

## Formats

| Format | Reader |
| --- | --- |
| EPUB | Paginated, swipe to turn, full typography controls |
| MOBI / AZW3 | Same reader (DRM-free; HUFF/CDIC-compressed files need converting to EPUB) |
| PDF | Swipe pages, pinch / double-tap zoom, text search on Android 15+ |
| CBZ / CBR | Swipe pages, zoom, right-to-left manga mode |

## Reading

- Swipe left/right to turn pages; tap the left or right edge to turn, tap the middle for controls.
- Volume buttons turn pages (Settings → Reading display).
- Themes: Paper, Sepia, Sage, Slate, Night, Black — with an automatic night theme after dark.
- Fonts: book default, Literata, Noto Serif, Inter, Atkinson Hyperlegible, Mono. Size, line spacing, margins, justification, hyphenation.
- Two-page spread on tablets in landscape.
- Progress syncs to Athena, so you pick up where you left off on any device.

## Extras

1. **Home ↔ Tailscale failover** — two server addresses, chosen automatically.
2. **Offline downloads** — keep books on the device; recently opened books are cached too.
3. **Auto-download next in series** — near the end of a book, the next one downloads in the background.
4. **"Up next" when you finish** — marks the book finished on Athena and offers the next in the series.
5. **Highlights & notes** — long-press text → Highlight / Note / Copy / Define / Share; four colours; export everything as Markdown.
6. **Bookmarks** for every format.
7. **Search inside a book** with snippets that jump straight to the match.
8. **Read aloud** — the phone's text-to-speech reads from the current page, highlights the paragraph and turns pages for you.
9. **Reading stats** — daily goal ring, streaks, two-week chart, pace, and "time left in chapter / book" from your own reading speed.
10. **Night light** — warmth filter and in-app brightness on top of the dark themes.

## Building

Every push to `main` builds a signed release APK in GitHub Actions and publishes it as release `v1.0.<run>`. The signing key is in `keystore/` and must never change, or Android will refuse the update.
