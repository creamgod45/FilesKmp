#!/usr/bin/env bash
# 打包 FilesKmp：免安裝版 ZIP、MSI 安裝檔與 SHA256 檢查碼，放到 dist/。
# 在 Windows 的 Git Bash / MSYS2 中執行（本專案只支援 Windows x64 / ARM64）。
# 步驟與 .github/workflows/release-packages.yml 相同；產物架構取決於執行 Gradle 的 JDK。
#
# Gradle 會下載 FFmpeg 放進 build/FilesKmp/ffmpeg，給捷徑的影片動作使用（--no-ffmpeg 不內附）。
#
# 用法：./package.sh [-v 版本] [--jdk JDK資料夾] [--wix WiX資料夾] [--skip-msi] [--smoke-test] [--clean] [--no-ffmpeg | --ffmpeg ffmpeg.exe] [-o 輸出資料夾]
set -euo pipefail

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

選項：
  -v, --version X.Y.Z   版本號（預設取 build.gradle.kts 的預設值）
      --jdk DIR         含 bin/jpackage.exe 的 JDK
      --wix DIR         含 candle.exe 的 WiX 3.14（找不到時自動下載到 %LOCALAPPDATA%\wix314）
      --skip-msi        只打包免安裝版 ZIP（不需要 WiX）
      --smoke-test      打包後以 --screenshot 啟動一次程式
      --clean           先執行 gradlew clean
      --no-ffmpeg       不內附 FFmpeg（影片動作改用電腦上安裝的 FFmpeg）
      --ffmpeg FILE     內附這個 ffmpeg.exe，而不是下載 BtbN 的版本
  -o, --out DIR         產物資料夾（預設 dist）
  -h, --help            顯示說明
EOF
}

VERSION="" JDK="" WIX="" SKIP_MSI=0 SMOKE=0 CLEAN=0 OUT_DIR="dist" NO_FFMPEG=0 FFMPEG_EXE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--version) VERSION="${2:?}"; shift 2 ;;
    --jdk)        JDK="${2:?}"; shift 2 ;;
    --wix)        WIX="${2:?}"; shift 2 ;;
    --skip-msi)   SKIP_MSI=1; shift ;;
    --smoke-test) SMOKE=1; shift ;;
    --clean)      CLEAN=1; shift ;;
    --no-ffmpeg)  NO_FFMPEG=1; shift ;;
    --ffmpeg)     FFMPEG_EXE="${2:?}"; shift 2 ;;
    -o|--out)     OUT_DIR="${2:?}"; shift 2 ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "未知的參數：$1" >&2; usage >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")"
step() { printf '\033[36m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31m錯誤：%s\033[0m\n' "$*" >&2; exit 1; }

command -v cygpath >/dev/null || die "請在 Windows 的 Git Bash / MSYS2 中執行"
win()  { cygpath -w "$1"; }
unix() { cygpath -u "$1"; }

# --- 版本 -------------------------------------------------------------------
if [[ -n "$VERSION" ]]; then
  VERSION="${VERSION#v}"
  [[ "$VERSION" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})$ ]] || die "版本須為 x.y.z，且不可含前導零：$VERSION"
  IFS=. read -r MA MI PA <<<"$VERSION"
  (( MA >= 1 && MA <= 255 && MI <= 255 && PA <= 65535 )) || die "MSI 版本範圍：major 1..255、minor 0..255、patch 0..65535"
else
  VERSION="$(sed -n 's/.*gradleProperty("appVersion")\.getOrElse("\([^"]*\)").*/\1/p' build.gradle.kts | head -n1)"
  [[ -n "$VERSION" ]] || die "無法從 build.gradle.kts 讀取預設版本，請用 -v 指定"
fi

# --- JDK --------------------------------------------------------------------
find_jdk() {
  local c candidates=()
  [[ -n "$JDK" ]] && candidates+=("$(unix "$JDK")")
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$(unix "$JAVA_HOME")")
  local up; up="$(unix "$USERPROFILE")"
  if [[ -d "$up/.jdks" ]]; then
    while IFS= read -r c; do candidates+=("$c"); done < <(ls -1d "$up/.jdks"/*/ 2>/dev/null | sort -r)
  fi
  local pf vendor
  for pf in "${ProgramW6432:-}" "${PROGRAMFILES:-}"; do
    [[ -n "$pf" ]] || continue
    for vendor in Java "Eclipse Adoptium" Microsoft Zulu "Amazon Corretto" BellSoft; do
      local d; d="$(unix "$pf")/$vendor"
      [[ -d "$d" ]] || continue
      while IFS= read -r c; do candidates+=("$c"); done < <(ls -1d "$d"/*/ 2>/dev/null | sort -r)
    done
  done
  for c in "${candidates[@]}"; do
    c="${c%/}"
    if [[ -f "$c/bin/java.exe" && -f "$c/bin/jpackage.exe" ]]; then echo "$c"; return; fi
  done
  die "找不到含 jpackage.exe 的 JDK（需要 JDK 21+）。請安裝 JDK 或用 --jdk 指定。"
}

# --- WiX --------------------------------------------------------------------
find_wix() {
  local d dirs=()
  for d in "$WIX" "${WIX_ENV:-}"; do [[ -n "$d" ]] && dirs+=("$(unix "$d")" "$(unix "$d")/bin"); done
  dirs+=("$(unix "$LOCALAPPDATA")/wix314")
  local pf
  for pf in "$(unix "C:\\Program Files (x86)")" "$(unix "${PROGRAMFILES:-C:\\Program Files}")"; do
    [[ -d "$pf" ]] || continue
    while IFS= read -r d; do dirs+=("${d%/}/bin"); done < <(ls -1d "$pf"/WiX\ Toolset*/ 2>/dev/null | sort -r)
  done
  for d in "${dirs[@]}"; do
    if [[ -f "$d/candle.exe" ]]; then echo "$d"; return; fi
  done
}

install_wix() {
  local dest zip
  dest="$(unix "$LOCALAPPDATA")/wix314"
  zip="$(mktemp -u)-wix314.zip"
  step "下載 WiX 3.14.1 到 $(win "$dest")" >&2
  curl -fL --retry 3 -o "$zip" https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip >&2
  mkdir -p "$dest"
  powershell.exe -NoProfile -Command "Expand-Archive -LiteralPath '$(win "$zip")' -DestinationPath '$(win "$dest")' -Force" >&2
  rm -f "$zip"
  echo "$dest"
}

WIX_ENV="${WIX:-}"
JDK="$(find_jdk)"
export JAVA_HOME; JAVA_HOME="$(win "$JDK")"
export PATH="$JDK/bin:$PATH"

OS_ARCH="$(sed -n 's/^OS_ARCH="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$JDK/release" 2>/dev/null | head -n1)"
case "$OS_ARCH" in
  amd64|x86_64)  ARCH=x64;   EXPECTED=8664 ;;
  aarch64|arm64) ARCH=arm64; EXPECTED=aa64 ;;
  *) die "不支援的 JDK 架構：${OS_ARCH:-未知}" ;;
esac

GRADLE_ARGS=("-PappVersion=$VERSION" "-PjdkHome=$JAVA_HOME" --console=plain)
if (( NO_FFMPEG )); then GRADLE_ARGS+=(-PnoFfmpeg)
elif [[ -n "$FFMPEG_EXE" ]]; then
  [[ -f "$FFMPEG_EXE" ]] || die "找不到 $FFMPEG_EXE"
  GRADLE_ARGS+=("-PffmpegExe=$(win "$(cd "$(dirname "$FFMPEG_EXE")" && pwd)/$(basename "$FFMPEG_EXE")")")
fi
if (( ! SKIP_MSI )); then
  WIX_DIR="$(find_wix)"
  [[ -n "$WIX_DIR" ]] || WIX_DIR="$(install_wix)"
  export PATH="$WIX_DIR:$PATH"
  GRADLE_ARGS+=("-PwixHome=$(win "$WIX_DIR")")
fi

echo "FilesKmp $VERSION · windows-$ARCH"
echo "JDK: $JAVA_HOME"
(( SKIP_MSI )) || echo "WiX: $(win "$WIX_DIR")"

# --- 建置 -------------------------------------------------------------------
TASKS=()
(( CLEAN )) && TASKS+=(clean)
if (( SKIP_MSI )); then TASKS+=(windowsApp); else TASKS+=(windowsMsi); fi
step "gradlew ${TASKS[*]}"
./gradlew "${TASKS[@]}" "${GRADLE_ARGS[@]}" || die "Gradle 建置失敗"

# --- 驗證架構 ---------------------------------------------------------------
step "檢查 launcher 與 runtime 架構"
pe_machine() {   # PE 標頭的 Machine 欄位（小端序十六進位）
  local off
  off=$(( $(od -An -tu4 -j60 -N4 "$1" | tr -d ' ') ))
  od -An -tx2 -j$(( off + 4 )) -N2 "$1" | tr -d ' '
}
CHECKED=(build/FilesKmp/FilesKmp.exe build/FilesKmp/runtime/bin/server/jvm.dll)
# 只檢查下載的 FFmpeg；自行指定的 ffmpeg.exe 可以是 x64（ARM64 Windows 能模擬執行）。
(( NO_FFMPEG )) || [[ -n "$FFMPEG_EXE" ]] || CHECKED+=(build/FilesKmp/ffmpeg/ffmpeg.exe)
for f in "${CHECKED[@]}"; do
  [[ -f "$f" ]] || die "找不到 $f"
  m="$(pe_machine "$f")"
  [[ "$m" == "$EXPECTED" ]] || die "架構不符：$f（0x$m，預期 0x$EXPECTED）"
done

if (( SMOKE )); then
  step "啟動測試"
  shot="$(win "$(mktemp -u)-fileskmp-smoke.png")"
  powershell.exe -NoProfile -Command "
    \$p = Start-Process -FilePath '$(win "$PWD/build/FilesKmp/FilesKmp.exe")' -ArgumentList @('--screenshot', '\"$shot\"') -PassThru -WindowStyle Hidden
    if (-not \$p.WaitForExit(60000)) { \$p.Kill(); Write-Error '程式啟動逾時'; exit 1 }
    if (\$p.ExitCode -ne 0 -or -not (Test-Path '$shot') -or (Get-Item '$shot').Length -lt 1000) { Write-Error \"程式啟動失敗（exit \$(\$p.ExitCode)）\"; exit 1 }
    Remove-Item '$shot'
  " || die "啟動測試失敗"
fi

# --- 收集產物 ---------------------------------------------------------------
step "收集產物到 $OUT_DIR"
PREFIX="FilesKmp-$VERSION-windows-$ARCH"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
OUTPUTS=()
if (( ! SKIP_MSI )); then
  cp -f "build/installers/$PREFIX.msi" "$OUT_DIR/"
  OUTPUTS+=("$OUT_DIR/$PREFIX.msi")
fi
ZIP="$OUT_DIR/$PREFIX-portable.zip"
rm -f "$ZIP"
powershell.exe -NoProfile -Command "Compress-Archive -Path '$(win "$PWD/build/FilesKmp")' -DestinationPath '$(win "$ZIP")'" || die "壓縮失敗"
OUTPUTS+=("$ZIP")

SUMS="$OUT_DIR/$PREFIX-SHA256SUMS.txt"
( cd "$OUT_DIR" && for f in "${OUTPUTS[@]}"; do sha256sum "$(basename "$f")"; done ) | sed 's/ \*/  /' > "$SUMS"
OUTPUTS+=("$SUMS")

echo
printf '\033[32m完成：\033[0m\n'
for f in "${OUTPUTS[@]}"; do
  printf '  %s  (%s)\n' "$(win "$f")" "$(du -h "$f" | cut -f1)"
done
