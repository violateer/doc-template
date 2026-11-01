#!/usr/bin/env bash
# ============================================================================
#  把 docs/public/demo 下的示例模板刷进系统（模板 + 数据块 + 字典 + 模板文件）
#
#  用法：
#    scripts/seed-demo.sh
#    scripts/seed-demo.sh --base-url http://localhost:8080
#    scripts/seed-demo.sh --dry-run
#    scripts/seed-demo.sh --only WordStandard,ExcelDataRows
#
#  参数原样转给 seed-demo.mjs，完整选项见 scripts/seed-demo.mjs --help
# ============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "[x] 没找到 Node.js，请先安装 Node 18+ 并加进 PATH" >&2
  exit 1
fi

exec node "$DIR/seed-demo.mjs" "$@"
