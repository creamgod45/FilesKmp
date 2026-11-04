# FilesKmp 捷徑格式 — fileskmp.shortcut/v1

A shortcut is a UTF-8 JSON file with the extension `.fkflow.json`:

```json
{
  "schema": "fileskmp.shortcut/v1",
  "name": "相片依拍攝日期整理",
  "description": "把選取的相片複製到「年\\月」資料夾，依拍攝日期（沒有時用修改日期）。",
  "icon": "photo",
  "hotkey": "",
  "accepts": ["files", "folder"],
  "params": [
    {
      "id": "dest",
      "label": "目的資料夾",
      "type": "folder",
      "default": "{pictures}\\依日期整理"
    }
  ],
  "steps": [
    {
      "action": "files.selected"
    },
    {
      "action": "files.filter",
      "args": {
        "query": "ext:jpg;jpeg;png;heic;webp;gif"
      }
    },
    {
      "action": "stop.if",
      "args": {
        "when": "empty",
        "message": "選取的項目中沒有相片"
      }
    },
    {
      "action": "file.copy",
      "args": {
        "to": "{param.dest}\\{item.taken:yyyy}\\{item.taken:MM}"
      }
    },
    {
      "action": "notify",
      "args": {
        "title": "相片整理完成",
        "text": "已複製 {count} 張相片"
      }
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `schema` | Always `fileskmp.shortcut/v1` |
| `name`, `description`, `icon` | Shown in the shortcut list |
| `hotkey` | Optional, e.g. `Ctrl+Alt+1` |
| `accepts` | `files`, `folder`, `text`, `none` |
| `params` | Values asked before running: `id`, `label`, `type` (`text`/`folder`/`path`), `default`; used as `{param.id}` |
| `steps` | Actions run in order: `action`, `args`, optional `enabled: false` |

## Tokens

`{param.x}` `{var.x}` `{count}` `{input}` `{first}` `{date:yyyy-MM-dd}` `{folder}` `{home}` `{desktop}` `{documents}` `{downloads}` `{pictures}` `{clipboard}`

Per item: `{item}` `{item.name}` `{item.stem}` `{item.ext}` `{item.parent}` `{item.folder}` `{item.index:000}` `{item.modified:fmt}` `{item.created:fmt}` `{item.taken:fmt}` `{item.size}` `{item.width}` `{item.height}` `{item.camera}` `{item.artist}` `{item.album}` `{item.title}` `{item.track:00}` `{item.field:Name}`

## Actions

### 輸入與文字/Input & text

- **`files.selected`** — Get selected items / 取得選取的項目. The selected files and folders (the open folder when nothing is selected).
- **`files.list`** — List folder / 列出資料夾內容. Items in a folder, optionally filtered and including subfolders.
  - `folder` (folder) Folder, default `{folder}`
  - `query` (text) Filter (search syntax)
  - `recursive` (bool) Include subfolders, default `false`
- **`text.set`** — Text / 文字. Replaces the values with this text (tokens allowed).
  - `text` (multiline) Text
- **`text.replace`** — Replace text / 取代文字. Replaces text in every value; regex allowed.
  - `find` (text) Find
  - `replace` (text) Replace with
  - `regex` (bool) Regex, default `false`
- **`text.split`** — Split text / 分割文字. Splits text into values (by line by default).
  - `separator` (text) Separator (\n = new line), default `\n`
- **`text.join`** — Join text / 合併文字. Joins all values into one text.
  - `separator` (text) Separator (\n = new line), default `\n`
- **`clipboard.get`** — Get clipboard / 取得剪貼簿. The text on the clipboard.
- **`clipboard.set`** — Copy to clipboard / 複製到剪貼簿. Puts the values (one per line) on the clipboard.
- **`ui.input`** — Ask for input / 要求輸入. Asks for text while running.
  - `label` (text) Prompt, default `輸入文字`
  - `default` (text) Default
- **`ui.choose`** — Choose from list / 從清單選擇. Pick one of the current values.
  - `title` (text) Title, default `選擇一項`

### 檔案/Files

- **`files.filter`** — Filter / 篩選. Keeps the items matching the search syntax (ext:, size:, dm:, wildcards, regex: …).
  - `query` (text) Condition, default `ext:jpg;png`
- **`files.sort`** — Sort / 排序. Sorts by name, size or date.
  - `by` (choice: name|size|modified|created) By, default `name`
  - `descending` (bool) Descending, default `false`
- **`files.limit`** — Keep first / 取前幾項. Keeps the first N values.
  - `count` (number) Count, default `10`
- **`file.copy`** — Copy to / 複製到 *(changes files or the system; skipped in a dry run)*. Copies each item into a folder (tokens like {item.taken:yyyy} make subfolders).
  - `to` (folder) Destination folder, default `{desktop}\Copied`
- **`file.move`** — Move to / 移動到 *(changes files or the system; skipped in a dry run)*. Moves each item into a folder (tokens allowed).
  - `to` (folder) Destination folder, default `{folder}\Moved`
- **`file.rename`** — Rename / 重新命名 *(changes files or the system; skipped in a dry run)*. Renames each item from a template, e.g. {item.taken:yyyy-MM-dd}_{item.index:000}.{item.ext}.
  - `name` (text) New name, default `{item.stem}_{item.index:000}.{item.ext}`
- **`file.delete`** — Delete / 刪除 *(changes files or the system; skipped in a dry run)*. Deletes each item (to the Recycle Bin by default).
  - `recycle` (bool) To the Recycle Bin, default `true`
- **`folder.create`** — Create folder / 建立資料夾 *(changes files or the system; skipped in a dry run)*. Creates a folder (tokens allowed); the result is that folder.
  - `path` (folder) Path, default `{folder}\New folder`
- **`file.zip`** — Make a zip / 壓縮成 zip *(changes files or the system; skipped in a dry run)*. Puts all items into one zip file.
  - `to` (path) Zip file, default `{desktop}\archive-{date:yyyyMMdd-HHmm}.zip`
- **`file.unzip`** — Extract zip / 解壓縮 *(changes files or the system; skipped in a dry run)*. Extracts each zip file.
  - `to` (folder) Destination, default `{item.parent}\{item.stem}`
- **`file.readText`** — Read text / 讀取文字內容. The text of each file (up to 1 MiB).
- **`file.writeText`** — Write text file / 寫入文字檔 *(changes files or the system; skipped in a dry run)*. Writes the values to a file.
  - `path` (path) File, default `{desktop}\output.txt`
  - `append` (bool) Append, default `false`
- **`file.hash`** — Hash / 計算雜湊值. Each file's hash (SHA-256 / SHA-1 / MD5).
  - `algorithm` (choice: SHA-256|SHA-1|MD5) Algorithm, default `SHA-256`
- **`file.info`** — File info / 檔案資訊. Name, size and date of each item (tab separated).
- **`app.navigate`** — Show in FilesKmp / 在 FilesKmp 中顯示. Opens the first item's folder here and selects the items.

### 影像/Images

- **`image.convert`** — Convert images / 轉換影像格式 *(changes files or the system; skipped in a dry run)*. Converts each picture to PNG, JPEG or WebP.
  - `format` (choice: png|jpg|webp) Format, default `webp`
  - `quality` (number) Quality (1–100), default `85`
  - `to` (folder) Destination, default `{item.parent}`
- **`image.resize`** — Resize images / 縮小影像 *(changes files or the system; skipped in a dry run)*. Scales pictures down so the longest side fits.
  - `max` (number) Longest side (px), default `1920`
  - `to` (folder) Destination, default `{item.parent}\resized`

### Windows/Windows

- **`open`** — Open / 開啟 *(changes files or the system; skipped in a dry run)*. Opens each item in its default app.
- **`open.with`** — Open with app / 以應用程式開啟 *(changes files or the system; skipped in a dry run)*. Opens the items with a program.
  - `app` (path) Program (e.g. notepad.exe, code), default `notepad.exe`
- **`url.open`** — Open URL / 開啟網址 *(changes files or the system; skipped in a dry run)*. Opens a web address (tokens allowed).
  - `url` (text) URL, default `https://www.bing.com/search?q={first}`
- **`app.settings`** — Open a Settings page / 開啟 Windows 設定頁 *(changes files or the system; skipped in a dry run)*. e.g. display, bluetooth, network-wifi, storagesense, windowsupdate.
  - `page` (choice: display|sound|bluetooth|network-wifi|storagesense|windowsupdate|apps-volume|personalization-background|defaultapps|privacy) Page, default `display`
- **`shell.run`** — Run script / 執行指令碼 *(changes files or the system; skipped in a dry run)*. Runs PowerShell or cmd; the values are in the FILES_ITEMS environment variable (one per line); the output becomes the new values.
  - `shell` (choice: powershell|cmd) Shell, default `powershell`
  - `script` (multiline) Script, default `$env:FILES_ITEMS -split "`n" | ForEach-Object { (Get-Item $_).Length }`
- **`notify`** — Show notification / 顯示通知. Shows a Windows notification.
  - `title` (text) Title, default `FilesKmp`
  - `text` (text) Text, default `{count} 個項目已完成`
- **`speak`** — Speak / 朗讀文字 *(changes files or the system; skipped in a dry run)*. Reads text aloud with Windows' voice.
  - `text` (text) Text, default `{first}`
- **`wallpaper.set`** — Set as desktop background / 設為桌面背景 *(changes files or the system; skipped in a dry run)*. Makes the first picture the desktop background.
- **`screen.lock`** — Lock the PC / 鎖定電腦 *(changes files or the system; skipped in a dry run)*. Locks Windows (like Win+L).

### 流程控制/Control

- **`ui.confirm`** — Ask to continue / 要求確認. Shows a message; Cancel stops the shortcut.
  - `message` (text) Message, default `要繼續處理 {count} 個項目嗎？`
- **`var.set`** — Set variable / 設定變數. Saves the values as a variable: {var.name} or Get variable.
  - `name` (text) Name, default `saved`
- **`var.get`** — Get variable / 取得變數. Replaces the values with a variable.
  - `name` (text) Name, default `saved`
- **`stop.if`** — Stop if / 條件停止. Stops when there are no values (or when there are).
  - `when` (choice: empty|notEmpty) When, default `empty`
  - `message` (text) Message
- **`each`** — Repeat with each / 逐項重複. Runs the inner steps once per value and collects the results.
  - `steps` (steps) Steps

