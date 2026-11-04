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
| `steps` | Actions run in order (see *Steps*) |

## Steps

```json
{ "action": "file.hash", "input": "var:photos", "args": { "algorithm": "SHA-256" }, "saveAs": "hashes", "enabled": true }
```

| Field | Meaning |
|---|---|
| `action` | The action id (below) |
| `args` | The action's arguments; text may use tokens |
| `input` | What the step works on: omitted = the previous step's result, `nothing`, `shortcut` (what the shortcut was started with), `var:NAME` |
| `saveAs` | Also keeps the step's result in a variable, used as `{var.NAME}` or `input: "var:NAME"` |
| `enabled` | `false` skips the step |

## Values and types

Steps pass a **list of values** along. Every value is text; each action declares what it takes and leaves:

| Type | Values are |
|---|---|
| `files` | Full paths of files or folders |
| `text` | Any text (one value per item; `text.split` / `text.join` convert) |
| `number` | Decimal numbers (`3`, `2.5`) |
| `true/false` | `true` or `false` |
| `dictionary/JSON` | A JSON document (`{"a": 1}` or `[1, 2]`) |
| `date` | `yyyy-MM-dd HH:mm:ss` unless a format is chosen |
| `nothing` | The action ignores its input / leaves no values |
| `same as input` | The values are handed on unchanged (for actions done for their effect) |

## Tokens

`{param.x}` `{var.x}` `{count}` `{input}` `{first}` `{last}` `{date:yyyy-MM-dd}` `{folder}` `{home}` `{desktop}` `{documents}` `{downloads}` `{pictures}` `{music}` `{videos}` `{temp}` `{clipboard}` `{env.NAME}` `{newline}` `{tab}`

Inside `each` / `repeat`: `{var.index}` (1, 2, …) and `{var.item}` (the current value).

Per item: `{item}` `{item.name}` `{item.stem}` `{item.ext}` `{item.parent}` `{item.folder}` `{item.index:000}` `{item.modified:fmt}` `{item.created:fmt}` `{item.taken:fmt}` `{item.size}` `{item.width}` `{item.height}` `{item.camera}` `{item.artist}` `{item.album}` `{item.title}` `{item.track:00}` `{item.field:Name}`

## Actions

### 取得輸入/Get input

- **`files.selected`** — Get selected items / 取得選取的項目. The selected files and folders (the open folder when nothing is selected).  
  Input: *nothing* → Output: *files*
- **`files.list`** — List folder / 列出資料夾內容. Items in a folder, optionally filtered and including subfolders.  
  Input: *nothing* → Output: *files*
  - `folder` (folder) Folder, default `{folder}`
  - `query` (text) Filter (search syntax)
  - `recursive` (bool) Include subfolders, default `false`
- **`clipboard.get`** — Get clipboard / 取得剪貼簿. The text on the clipboard.  
  Input: *nothing* → Output: *text*
- **`ui.input`** — Ask for input / 要求輸入. Asks for text while running.  
  Input: *nothing* → Output: *text*
  - `label` (text) Prompt, default `輸入文字`
  - `default` (text) Default
- **`ui.choose`** — Choose from list / 從清單選擇. Pick one of the current values.  
  Input: *anything* → Output: *same as input*
  - `title` (text) Title, default `選擇一項`
- **`ui.pickFiles`** — Pick files / 選擇檔案. Opens a file picker while running.  
  Input: *nothing* → Output: *files*
  - `title` (text) Title, default `選擇檔案`
  - `multiple` (bool) Allow several, default `true`
  - `folder` (folder) Start in, default `{folder}`
- **`ui.pickFolder`** — Pick a folder / 選擇資料夾. Opens a folder picker while running.  
  Input: *nothing* → Output: *files*
  - `title` (text) Title, default `選擇資料夾`
  - `folder` (folder) Start in, default `{folder}`
- **`file.get`** — Files from paths / 以路徑取得檔案. Treats each text value as a file or folder path (tokens allowed).  
  Input: *text* → Output: *files*
  - `paths` (multiline) Paths (one per line; empty = the input)
  - `mustExist` (bool) Only existing ones, default `true`

### 文字/Text

- **`text.set`** — Text / 文字. Replaces the values with this text (tokens allowed).  
  Input: *nothing* → Output: *text*
  - `text` (multiline) Text
- **`text.replace`** — Replace text / 取代文字. Replaces text in every value; regex allowed.  
  Input: *text* → Output: *text*
  - `find` (text) Find
  - `replace` (text) Replace with
  - `regex` (bool) Regex, default `false`
- **`text.split`** — Split text / 分割文字. Splits text into values (by line by default).  
  Input: *text* → Output: *text*
  - `separator` (text) Separator (\n = new line), default `\n`
- **`text.join`** — Join text / 合併文字. Joins all values into one text.  
  Input: *text* → Output: *text*
  - `separator` (text) Separator (\n = new line), default `\n`
- **`clipboard.set`** — Copy to clipboard / 複製到剪貼簿. Puts the values (one per line) on the clipboard.  
  Input: *text* → Output: *same as input*
- **`text.find`** — Find in text / 尋找文字. Every match in each value (regex and capture groups allowed).  
  Input: *text* → Output: *text*
  - `find` (text) Find
  - `regex` (bool) Regex, default `false`
  - `group` (number) Group (0 = whole match), default `0`
  - `ignoreCase` (bool) Ignore case, default `true`
- **`text.test`** — Test text / 檢查文字. Contains, starts/ends with, equals, regex, compare…; filter the values or get true/false.  
  Input: *text* → Output: *depends on the arguments*
  - `op` (choice: contains|notContains|equals|notEquals|startsWith|endsWith|matches|isEmpty|notEmpty|>|>=|<|<=) Condition, default `contains`
  - `value` (text) Value
  - `ignoreCase` (bool) Ignore case, default `true`
  - `mode` (choice: filter|reject|each|any|all) Result, default `filter`
- **`text.indexOf`** — Position in text / 文字位置. Where the text appears in each value (from 1; 0 = not found).  
  Input: *text* → Output: *number*
  - `find` (text) Find
  - `from` (choice: first|last) Which, default `first`
  - `ignoreCase` (bool) Ignore case, default `true`
- **`text.substring`** — Part of text / 擷取部分文字. Characters from a position (negative = from the end) for a length.  
  Input: *text* → Output: *text*
  - `start` (number) Start (from 1), default `1`
  - `length` (text) Length (empty = to the end)
- **`text.case`** — Change case / 變更大小寫. UPPER, lower, Title, Sentence, camelCase, snake_case, kebab-case.  
  Input: *text* → Output: *text*
  - `to` (choice: upper|lower|title|sentence|camel|snake|kebab) To, default `upper`
- **`text.trim`** — Trim / 去除空白. Removes surrounding spaces, or collapses runs of spaces.  
  Input: *text* → Output: *text*
  - `mode` (choice: both|start|end|collapse|removeEmpty) How, default `both`
- **`text.length`** — Text length / 文字長度. Characters (or lines, words) in each value.  
  Input: *text* → Output: *number*
  - `count` (choice: characters|lines|words|bytes) Count, default `characters`
- **`text.template`** — Combine text / 組合文字. Applies a template to each value: {item} is the value, {item.index} its number.  
  Input: *anything* → Output: *text*
  - `template` (multiline) Template, default `{item.index}. {item}`

### 清單與字典/Lists & dictionaries

- **`files.limit`** — Keep first / 取前幾項. Keeps the first N values.  
  Input: *anything* → Output: *same as input*
  - `count` (number) Count, default `10`
- **`list.make`** — List / 清單. Replaces the values with these items (one per line, tokens allowed).  
  Input: *nothing* → Output: *text*
  - `items` (multiline) Items
- **`list.item`** — Get item from list / 取得清單項目. First, last, item N (negative = from the end), random or a range.  
  Input: *anything* → Output: *same as input*
  - `which` (choice: first|last|index|random|range) Get, default `first`
  - `index` (number) Item (from 1), default `1`
  - `to` (number) To (range), default `3`
- **`list.count`** — Count / 計數. How many values there are.  
  Input: *anything* → Output: *number*
- **`list.reverse`** — Reverse / 反轉順序. Reverses the order.  
  Input: *anything* → Output: *same as input*
- **`list.unique`** — Remove duplicates / 移除重複. Keeps the first of each value.  
  Input: *anything* → Output: *same as input*
  - `ignoreCase` (bool) Ignore case, default `false`
- **`list.shuffle`** — Shuffle / 隨機排列. Puts the values in random order.  
  Input: *anything* → Output: *same as input*
- **`list.sort`** — Sort values / 排序文字. Sorts as text, naturally (file2 < file10), as numbers or by length.  
  Input: *anything* → Output: *same as input*
  - `by` (choice: natural|text|number|length) By, default `natural`
  - `descending` (bool) Descending, default `false`
- **`list.add`** — Add to list / 加入項目. Adds items (one per line) or a variable's values at the start or end.  
  Input: *anything* → Output: *same as input*
  - `items` (multiline) Items
  - `variable` (text) Or a variable
  - `at` (choice: end|start) Where, default `end`
- **`list.remove`** — Remove from list / 移除項目. Removes item N (negative = from the end) or values equal to a text.  
  Input: *anything* → Output: *same as input*
  - `index` (number) Item (0 = not by position), default `0`
  - `equals` (text) Or equal to
- **`json.make`** — Dictionary / 字典. Makes a dictionary (JSON object) from key=value lines; numbers, true/false and JSON keep their type.  
  Input: *anything* → Output: *dictionary/JSON*
  - `entries` (multiline) Entries (key=value), default `name={item.name}
size={item.size}`
  - `perItem` (bool) One per value, default `false`
  - `asText` (bool) Everything as text, default `false`
- **`json.get`** — Get dictionary value / 取得字典值. Gets values by path: name, items[0].title, items[*].id; arrays become several values.  
  Input: *dictionary/JSON* → Output: *text*
  - `path` (text) Path, default `name`
- **`json.set`** — Set dictionary value / 設定字典值. Sets a value at a path (created when missing).  
  Input: *dictionary/JSON* → Output: *dictionary/JSON*
  - `path` (text) Path, default `name`
  - `value` (text) Value
  - `asText` (bool) As text, default `false`
- **`json.keys`** — Dictionary keys / 字典的鍵. Every key of each dictionary.  
  Input: *dictionary/JSON* → Output: *text*
- **`json.values`** — Dictionary values / 字典的值. Every value of each dictionary (or array).  
  Input: *dictionary/JSON* → Output: *text*
- **`json.encode`** — To JSON / 轉成 JSON. Turns the values into JSON: an array, an object (key=value lines) or strings.  
  Input: *anything* → Output: *dictionary/JSON*
  - `as` (choice: array|object|string) As, default `array`
  - `pretty` (bool) Pretty, default `true`
- **`json.decode`** — From JSON / 解析 JSON. Reads JSON: arrays become values; objects are formatted or flattened to path=value.  
  Input: *text* → Output: *depends on the arguments*
  - `as` (choice: values|pretty|flatten) Result, default `values`

### 數字、日期與編碼/Numbers, dates & encoding

- **`number.calc`** — Calculate / 計算. Evaluates a formula: + - * / % ^, parentheses, round(x,2) floor ceil abs sqrt min max; {first}, {count}, or {item} per value.  
  Input: *anything* → Output: *number*
  - `formula` (text) Formula, default `{first} * 2`
  - `perItem` (bool) Once per value, default `false`
- **`number.stats`** — Statistics / 統計. Sum, average, minimum, maximum or median of the numbers.  
  Input: *number* → Output: *number*
  - `of` (choice: sum|average|min|max|median|count) Get, default `sum`
- **`number.format`** — Format number / 數字格式. Decimals, thousands separators, percent or file size (KB / MB).  
  Input: *number* → Output: *text*
  - `style` (choice: decimal|percent|fileSize) Style, default `decimal`
  - `decimals` (number) Decimals, default `2`
  - `grouping` (bool) Thousands separators, default `true`
- **`number.random`** — Random number / 隨機數字. Random whole numbers between a minimum and maximum.  
  Input: *nothing* → Output: *number*
  - `min` (number) Minimum, default `1`
  - `max` (number) Maximum, default `100`
  - `count` (number) How many, default `1`
- **`time.now`** — Current date / 目前日期時間. The date and time now; formats like yyyy-MM-dd HH:mm:ss, iso, epoch.  
  Input: *nothing* → Output: *date*
  - `format` (text) Format, default `yyyy-MM-dd HH:mm:ss`
- **`time.format`** — Format date / 格式化日期. Reads dates (many forms, epoch seconds or ms) and writes them in a format; also year, month, weekday…  
  Input: *date* → Output: *date*
  - `format` (text) Format (e.g. yyyy/MM/dd, EEEE, iso, epoch), default `yyyy-MM-dd`
- **`time.adjust`** — Adjust date / 調整日期. Adds (or with a negative amount, subtracts) time.  
  Input: *date* → Output: *date*
  - `amount` (number) Amount, default `1`
  - `unit` (choice: seconds|minutes|hours|days|weeks|months|years) Unit, default `days`
  - `format` (text) Format, default `yyyy-MM-dd HH:mm:ss`
- **`time.diff`** — Time between / 日期相差. How long from each date to another (now by default).  
  Input: *date* → Output: *number*
  - `to` (text) To, default `now`
  - `unit` (choice: seconds|minutes|hours|days|weeks|months|years) Unit, default `days`
- **`base64.encode`** — Base64 encode / Base64 編碼. Encodes text or file contents as Base64.  
  Input: *anything* → Output: *text*
  - `source` (choice: text|file) Source, default `text`
  - `urlSafe` (bool) URL-safe (- _), default `false`
- **`base64.decode`** — Base64 decode / Base64 解碼. Decodes Base64 to text, or saves it as files ({item.index} tokens allowed).  
  Input: *text* → Output: *depends on the arguments*
  - `to` (choice: text|file) Result, default `text`
  - `path` (path) File path, default `{desktop}\decoded-{item.index:00}.bin`

### 檔案/Files

- **`files.filter`** — Filter / 篩選. Keeps the items matching the search syntax (ext:, size:, dm:, wildcards, regex: …).  
  Input: *files* → Output: *files*
  - `query` (text) Condition, default `ext:jpg;png`
- **`files.sort`** — Sort / 排序. Sorts by name, size or date.  
  Input: *files* → Output: *files*
  - `by` (choice: name|size|modified|created) By, default `name`
  - `descending` (bool) Descending, default `false`
- **`file.copy`** — Copy to / 複製到 *(changes files or the system; skipped in a dry run)*. Copies each item into a folder (tokens like {item.taken:yyyy} make subfolders).  
  Input: *files* → Output: *files*
  - `to` (folder) Destination folder, default `{desktop}\Copied`
- **`file.move`** — Move to / 移動到 *(changes files or the system; skipped in a dry run)*. Moves each item into a folder (tokens allowed).  
  Input: *files* → Output: *files*
  - `to` (folder) Destination folder, default `{folder}\Moved`
- **`file.rename`** — Rename / 重新命名 *(changes files or the system; skipped in a dry run)*. Renames each item from a template, e.g. {item.taken:yyyy-MM-dd}_{item.index:000}.{item.ext}.  
  Input: *files* → Output: *files*
  - `name` (text) New name, default `{item.stem}_{item.index:000}.{item.ext}`
- **`file.delete`** — Delete / 刪除 *(changes files or the system; skipped in a dry run)*. Deletes each item (to the Recycle Bin by default).  
  Input: *files* → Output: *nothing*
  - `recycle` (bool) To the Recycle Bin, default `true`
- **`folder.create`** — Create folder / 建立資料夾 *(changes files or the system; skipped in a dry run)*. Creates a folder (tokens allowed); the result is that folder.  
  Input: *nothing* → Output: *files*
  - `path` (folder) Path, default `{folder}\New folder`
- **`file.zip`** — Make a zip / 壓縮成 zip *(changes files or the system; skipped in a dry run)*. Puts all items into one zip file.  
  Input: *files* → Output: *files*
  - `to` (path) Zip file, default `{desktop}\archive-{date:yyyyMMdd-HHmm}.zip`
- **`file.unzip`** — Extract zip / 解壓縮 *(changes files or the system; skipped in a dry run)*. Extracts each zip file.  
  Input: *files* → Output: *files*
  - `to` (folder) Destination, default `{item.parent}\{item.stem}`
- **`file.readText`** — Read text / 讀取文字內容. The text of each file (up to 1 MiB).  
  Input: *files* → Output: *text*
- **`file.writeText`** — Write text file / 寫入文字檔 *(changes files or the system; skipped in a dry run)*. Writes the values to a file.  
  Input: *text* → Output: *files*
  - `path` (path) File, default `{desktop}\output.txt`
  - `append` (bool) Append, default `false`
- **`file.hash`** — Hash / 計算雜湊值. Each file's hash (SHA-256 / SHA-1 / MD5) in lowercase hex: the hash alone (for comparing) or with the file name.  
  Input: *files* → Output: *text*
  - `algorithm` (choice: SHA-256|SHA-1|MD5) Algorithm, default `SHA-256`
  - `format` (choice: hash|hash+name) Result, default `hash`
- **`file.info`** — File info / 檔案資訊. Name, size and date of each item (tab separated).  
  Input: *files* → Output: *text*
- **`app.navigate`** — Show in FilesKmp / 在 FilesKmp 中顯示. Opens the first item's folder here and selects the items.  
  Input: *files* → Output: *same as input*
- **`file.create`** — Create file / 建立檔案 *(changes files or the system; skipped in a dry run)*. Creates a text file (with content; tokens allowed).  
  Input: *nothing* → Output: *files*
  - `path` (path) Path, default `{folder}\新文字文件.txt`
  - `content` (multiline) Content
  - `ifExists` (choice: rename|overwrite|skip) If it exists, default `rename`
- **`file.exists`** — File exists / 檢查檔案是否存在. Keeps the paths that exist (or don't), or gives true/false.  
  Input: *text* → Output: *depends on the arguments*
  - `mode` (choice: existing|missing|each|all) Result, default `existing`
- **`file.detail`** — Get file detail / 取得檔案詳細資料. One detail of each item: name, extension, folder, size, dates, kind, attributes…  
  Input: *files* → Output: *depends on the arguments*
  - `detail` (choice: name|stem|extension|parent|path|size|sizeText|modified|created|accessed|isFolder|readOnly|hidden|lines) Detail, default `name`
  - `format` (text) Date format, default `yyyy-MM-dd HH:mm:ss`

### 影像/Images

- **`image.convert`** — Convert images / 轉換影像格式 *(changes files or the system; skipped in a dry run)*. Converts each picture to PNG, JPEG or WebP.  
  Input: *files* → Output: *files*
  - `format` (choice: png|jpg|webp) Format, default `webp`
  - `quality` (number) Quality (1–100), default `85`
  - `to` (folder) Destination, default `{item.parent}`
- **`image.resize`** — Resize images / 縮小影像 *(changes files or the system; skipped in a dry run)*. Scales pictures down so the longest side fits.  
  Input: *files* → Output: *files*
  - `max` (number) Longest side (px), default `1920`
  - `to` (folder) Destination, default `{item.parent}\resized`

### 網路/Web

- **`url.open`** — Open URL / 開啟網址 *(changes files or the system; skipped in a dry run)*. Opens a web address (tokens allowed).  
  Input: *anything* → Output: *same as input*
  - `url` (text) URL, default `https://www.bing.com/search?q={first}`
- **`http.request`** — HTTP request / HTTP 請求. Calls a URL (GET / POST / PUT / PATCH / DELETE) with headers, JSON, a form or files; the result is the body, JSON, status, headers or a downloaded file. A dry run only sends GET.  
  Input: *anything* → Output: *depends on the arguments*
  - `method` (choice: GET|POST|PUT|PATCH|DELETE|HEAD) Method, default `GET`
  - `url` (text) URL, default `https://api.github.com/repos/creamgod45/FilesKmp`
  - `headers` (multiline) Headers (Name: value per line)
  - `bodyType` (choice: none|json|text|form|file|multipart) Body, default `none`
  - `body` (multiline) Body (JSON / text / form fields key=value)
  - `fileField` (text) File field (multipart), default `file`
  - `output` (choice: body|json|status|headers|file|all) Result, default `body`
  - `saveTo` (path) Save to (result = file), default `{downloads}`
  - `timeout` (number) Timeout (s), default `30`
  - `failOnError` (bool) Stop on 4xx / 5xx, default `true`

### 應用程式/Apps

- **`open`** — Open / 開啟 *(changes files or the system; skipped in a dry run)*. Opens each item in its default app.  
  Input: *files* → Output: *same as input*
- **`open.with`** — Open with app / 以應用程式開啟 *(changes files or the system; skipped in a dry run)*. Opens the items with a program.  
  Input: *files* → Output: *same as input*
  - `app` (path) Program (e.g. notepad.exe, code), default `notepad.exe`
- **`shell.run`** — Run script / 執行指令碼 *(changes files or the system; skipped in a dry run)*. Runs PowerShell or cmd; the values are in the FILES_ITEMS environment variable (one per line); the output becomes the new values.  
  Input: *anything* → Output: *text*
  - `shell` (choice: powershell|cmd) Shell, default `powershell`
  - `script` (multiline) Script, default `$env:FILES_ITEMS -split "`n" | ForEach-Object { (Get-Item $_).Length }`
- **`app.find`** — Find apps / 尋找應用程式. Searches the apps on this PC: Start menu, App Paths, Store apps and command-line tools on PATH.  
  Input: *nothing* → Output: *depends on the arguments*
  - `query` (text) Name contains
  - `kind` (choice: all|desktop|store|cli) Kind, default `all`
  - `output` (choice: name|target|json) Result, default `name`
  - `refresh` (bool) Scan again, default `false`
- **`app.launch`** — Start app / 啟動應用程式 *(changes files or the system; skipped in a dry run)*. Starts a program (name, path or Store app) with arguments; can wait and capture its output (CLI tools), exit code or process id. {input} in the arguments stands for all values; Once per value runs it for each.  
  Input: *anything* → Output: *depends on the arguments*
  - `app` (app) App, default `notepad`
  - `args` (text) Arguments, default `{input}`
  - `perItem` (bool) Once per value, default `false`
  - `wait` (bool) Wait until it ends, default `false`
  - `output` (choice: none|output|exitCode|pid) Result, default `none`
  - `timeout` (number) Time limit (s), default `120`
  - `workDir` (folder) Working folder
  - `admin` (bool) Run as administrator, default `false`
- **`app.cli`** — Get command-line options / 取得命令列介面. Runs the program's help (--help, -h, /?, help) and lists its options and subcommands, or returns the whole help text. Windowed programs aren't started.  
  Input: *nothing* → Output: *text*
  - `app` (app) App, default `winget`
  - `output` (choice: options|text) Result, default `options`
- **`app.running`** — Is app running / 應用程式是否在執行. With a name: true/false. Without: every running program.  
  Input: *nothing* → Output: *depends on the arguments*
  - `name` (text) Program (e.g. notepad)
- **`app.quit`** — Quit app / 結束應用程式 *(changes files or the system; skipped in a dry run)*. Asks a program to close (like clicking ×); Force ends it at once.  
  Input: *anything* → Output: *same as input*
  - `name` (text) Program (e.g. notepad)
  - `force` (bool) Force, default `false`
- **`app.sendKeys`** — Type into app / 對應用程式按鍵 *(changes files or the system; skipped in a dry run)*. Brings a window (title or program name) forward and types keys: text, {ENTER}, {TAB}, ^s (Ctrl+S), %{F4} (Alt+F4), + (Shift).  
  Input: *anything* → Output: *same as input*
  - `window` (text) Window title or program, default `notepad`
  - `keys` (text) Keys, default `{first}{ENTER}`

### 聲音與語音/Sound & speech

- **`sound.play`** — Play sound / 播放聲音. Plays a system sound or a sound file (wav, mp3, m4a, wma…).  
  Input: *anything* → Output: *same as input*
  - `sound` (choice: file|default|asterisk|exclamation|hand|question) Sound, default `file`
  - `file` (path) Sound file, default `{first}`
  - `wait` (bool) Wait until it ends, default `true`
- **`speak`** — Speak / 朗讀文字. Reads text aloud with a Windows voice (name or language like en-US), speed and volume, or saves a wav file.  
  Input: *text* → Output: *depends on the arguments*
  - `text` (multiline) Text, default `{input}`
  - `voice` (text) Voice (name or language; empty = default)
  - `rate` (number) Speed (-10 to 10), default `0`
  - `volume` (number) Volume (0–100), default `100`
  - `wait` (bool) Wait until done, default `true`
  - `saveTo` (path) Save as wav (empty = speak)
- **`speak.voices`** — List voices / 列出語音. The speech voices installed on this PC (name and language).  
  Input: *nothing* → Output: *text*

### Windows 系統/Windows

- **`app.settings`** — Open a Settings page / 開啟 Windows 設定頁 *(changes files or the system; skipped in a dry run)*. e.g. display, bluetooth, network-wifi, storagesense, windowsupdate.  
  Input: *nothing* → Output: *same as input*
  - `page` (choice: display|sound|bluetooth|network-wifi|storagesense|windowsupdate|apps-volume|personalization-background|defaultapps|privacy) Page, default `display`
- **`notify`** — Show notification / 顯示通知. Shows a Windows notification.  
  Input: *anything* → Output: *same as input*
  - `title` (text) Title, default `FilesKmp`
  - `text` (text) Text, default `{count} 個項目已完成`
- **`wallpaper.set`** — Set as desktop background / 設為桌面背景 *(changes files or the system; skipped in a dry run)*. Makes the first picture the desktop background.  
  Input: *files* → Output: *same as input*
- **`screen.lock`** — Lock the PC / 鎖定電腦 *(changes files or the system; skipped in a dry run)*. Locks Windows (like Win+L).  
  Input: *nothing* → Output: *same as input*
- **`volume`** — Volume / 音量. Gets or sets the speaker volume, turns it up/down, mutes; the result is the volume afterwards (0–100).  
  Input: *anything* → Output: *number*
  - `op` (choice: get|set|up|down|mute|unmute|toggleMute) Do, default `get`
  - `value` (number) Level / step, default `50`
- **`screenshot`** — Screenshot / 螢幕截圖 *(changes files or the system; skipped in a dry run)*. Captures all screens or the main one at full resolution as PNG, optionally to the clipboard too.  
  Input: *nothing* → Output: *files*
  - `area` (choice: all|primary) Area, default `all`
  - `delay` (number) Delay (s), default `0`
  - `to` (path) Save to, default `{pictures}\Screenshots\FilesKmp {date:yyyy-MM-dd HHmmss}.png`
  - `copy` (bool) Also copy, default `false`
- **`battery`** — Battery / 電池狀態. Battery percent, charging, plugged in, or everything (a dictionary).  
  Input: *nothing* → Output: *depends on the arguments*
  - `get` (choice: percent|charging|pluggedIn|all) Get, default `percent`
- **`dnd`** — Do not disturb / 勿擾（通知橫幅）. Turns Windows notification banners off/on (Windows has no public Focus / DND API; this is the main Notifications switch); the result is whether it's quiet now.  
  Input: *anything* → Output: *true/false*
  - `op` (choice: status|on|off|toggle) Do, default `status`

### 郵件與簡訊/Mail & messages

- **`email.send`** — Send email / 寄送電子郵件 *(changes files or the system; skipped in a dry run)*. Mails the current files as attachments. How: draft (an .eml opened in your mail app — works everywhere), outlook (classic Outlook shows or sends it), smtp (sends with the account set up under Accounts).  
  Input: *anything* → Output: *same as input*
  - `to` (text) To (comma separated)
  - `cc` (text) Cc
  - `bcc` (text) Bcc
  - `subject` (text) Subject, default `{count} 個檔案`
  - `body` (multiline) Message
  - `html` (bool) Message is HTML, default `false`
  - `attach` (bool) Attach the current files, default `true`
  - `method` (choice: draft|outlook|smtp) How, default `draft`
  - `sendNow` (bool) Send at once (outlook), default `false`
- **`sms.send`** — Send text message / 傳送簡訊 *(changes files or the system; skipped in a dry run)*. phone: opens the Windows SMS app (Phone Link) with the text filled in; attachments go on the clipboard to paste. twilio: sends with the Twilio account under Accounts; pictures must be public URLs (MMS).  
  Input: *anything* → Output: *same as input*
  - `to` (text) Phone number
  - `body` (multiline) Message, default `{input}`
  - `attach` (bool) Attach the current files / URLs, default `false`
  - `method` (choice: phone|twilio) How, default `phone`

### 流程控制/Control

- **`ui.confirm`** — Ask to continue / 要求確認. Shows a message; Cancel stops the shortcut.  
  Input: *anything* → Output: *same as input*
  - `message` (text) Message, default `要繼續處理 {count} 個項目嗎？`
- **`var.set`** — Set variable / 設定變數. Saves the values as a variable: {var.name} or Get variable.  
  Input: *anything* → Output: *same as input*
  - `name` (text) Name, default `saved`
- **`var.get`** — Get variable / 取得變數. Replaces the values with a variable.  
  Input: *nothing* → Output: *anything*
  - `name` (text) Name, default `saved`
- **`stop.if`** — Stop if / 條件停止. Stops when there are no values (or when there are).  
  Input: *anything* → Output: *same as input*
  - `when` (choice: empty|notEmpty) When, default `empty`
  - `message` (text) Message
- **`each`** — Repeat with each / 逐項重複. Runs the inner steps once per value and collects the results.  
  Input: *anything* → Output: *anything*
  - `steps` (steps) Steps
- **`if`** — If / 如果. Runs the Then steps when the condition holds, else the Otherwise steps.  
  Input: *anything* → Output: *anything*
  - `subject` (text) Check, default `{first}`
  - `op` (choice: contains|notContains|equals|notEquals|startsWith|endsWith|matches|isEmpty|notEmpty|>|>=|<|<=) Condition, default `notEmpty`
  - `value` (text) Value
  - `ignoreCase` (bool) Ignore case, default `true`
  - `then` (steps) Then
  - `else` (steps) Otherwise
- **`repeat`** — Repeat / 重複. Runs the inner steps N times ({var.index} is the round) and collects the results.  
  Input: *anything* → Output: *anything*
  - `times` (number) Times, default `3`
  - `steps` (steps) Steps
- **`wait`** — Wait / 等待. Pauses for some seconds (decimals allowed).  
  Input: *anything* → Output: *same as input*
  - `seconds` (number) Seconds, default `1`
- **`wait.skippable`** — Wait (any key skips) / 等待（按任意鍵略過）. Shows a countdown; any key or click continues early. {var.skipped} is true when skipped.  
  Input: *anything* → Output: *same as input*
  - `seconds` (number) Seconds, default `10`
  - `message` (text) Message, default `按任意鍵繼續…`
- **`stop`** — Stop shortcut / 結束捷徑. Ends the shortcut here with a message.  
  Input: *anything* → Output: *nothing*
  - `message` (text) Message
- **`flow.run`** — Run shortcut / 執行捷徑. Runs another shortcut with the values as its input; its final values are the result.  
  Input: *anything* → Output: *anything*
  - `shortcut` (flow) Shortcut
  - `params` (multiline) Parameters (id=value per line)

### 除錯/Debug

- **`debug.show`** — Show result / 顯示結果. Pauses to show the current values; continue or stop.  
  Input: *anything* → Output: *same as input*
  - `title` (text) Title, default `目前的值`
- **`debug.log`** — Log / 寫入記錄. Writes the values (up to 50) to the run log without pausing.  
  Input: *anything* → Output: *same as input*
  - `label` (text) Label, default `debug`

