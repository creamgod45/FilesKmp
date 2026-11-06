# Changelog

[繁體中文](CHANGELOG.md) | English · [README](README.en.md) · [User guide](docs/USER-GUIDE.en.md)

Released entries are based on local Git tags and commit history. Dates are the corresponding commit dates. Unreleased changes are not necessarily available for download. The default build version `1.3.0` is the next planned version and is not a release until it is tagged.

## Unreleased (1.3.0)

### Added

- "Video & audio" shortcut actions (FFmpeg): convert video (webm → mp4, ts → mp4 and more; streams that fit the new format are copied, not re-encoded; optionally scaled down to 1080p, 720p, 480p and so on in the same pass), compress (by quality or to a target size), scale, video info, and custom FFmpeg arguments. The installer and the portable build include FFmpeg (BtbN's static GPL build).
- Quick Look shows the text, tables and slides of Word (.docx, .doc), OpenDocument (.odt, .ods, .odp) and PowerPoint (.pptx) files itself; Excel files use the installed Office preview first and the built-in table otherwise.
- A running shortcut can be minimized to the task center, which shows its progress and latest log line and can bring it back or stop it.
- MSI installer artwork and product information, and local packaging scripts (`package.ps1`, `package.sh`).
- Chinese and English READMEs, user guides, and changelogs, with existing UI screenshots and a bilingual release template.

### Changed

- Shortcut AI assistant: when a check fails, the chat lists the actual problems and the assistant fixes them and retries; the spec includes worked designs (such as a video conversion with a chosen resolution and format).

### Fixed

- A maximized window no longer covers the taskbar (including on HiDPI displays).
- Long right-click menus in short windows open up or down to fit, and scroll when they still don't fit.
- Quick Look sizes Windows preview handlers in physical pixels and gives keyboard focus back to the file list after closing.

Source: commits after `v1.2.0` (`0c26a6f` to the present).

## [1.2.0] — 2026-11-05

### Added

- Expanded shortcut actions for text, dates, images, HTTP, apps, Windows, speech, email, and SMS, plus flow control and an action reference.
- AI-assisted workflow editing, provider configuration, tool permissions, and workflow checks.
- Custom keyboard bindings, conflict inspection, and settings/key migration reports with backups.

### Changed

- Background picture triggers are separate, combinable options, with migration from the old setting.

Source: tag `v1.2.0`, commit `32ef022` (PR #3).

## [1.0.1] — 2026-11-04

### Added

- Recycle Bin browsing and restoration, Quick Look, and Markdown previews.
- Shortcut workflows, instant search, and query syntax.
- Batch rename, safe deletion, and file metadata editing.
- Configurable Details columns and Git patch operations.

Source: tag `v1.0.1`, commit `a654e3f` (PR #2).

## [1.0.0] — 2026-11-04

### Added

- Initial tagged version: file browsing, tabs, split panes, layouts, tags, settings, customizable toolbar, thumbnails, and backgrounds.
- Windows MSI packaging and an application icon.
- Network locations and disk usage analysis/history.
- Qodana configuration and hidden/system file visibility support.

### Changed

- Rebuildable cache is managed separately from user settings and the program installation.

Source: tag `v1.0.0`, commit `c988a5d` (PR #1), and preceding initial commits.

[1.2.0]: https://github.com/creamgod45/FilesKmp/releases/tag/v1.2.0
[1.0.1]: https://github.com/creamgod45/FilesKmp/releases/tag/v1.0.1
[1.0.0]: https://github.com/creamgod45/FilesKmp/releases/tag/v1.0.0
