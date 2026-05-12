#!/bin/bash
# update-mc9y.sh - bbs.mc9y.net 资源更新脚本 (XenForo 2.x 表单登录模式)
# 用法: ./update-mc9y.sh <版本号> <更新标题> <更新内容> [JAR文件路径]
# 环境变量: MC9Y_USERNAME, MC9Y_PASSWORD
# 功能: 发布新版本 + 上传JAR附件 + 发布更新日志

set -euo pipefail

VERSION="${1:?用法: $0 <版本号> <更新标题> <更新内容> [JAR文件路径]}"
TITLE="${2:?缺少更新标题}"
MESSAGE="${3:?缺少更新内容}"
JAR_FILE="${4:-}"  # 可选: JAR文件路径
RESOURCE_ID="942"
BASE_URL="https://bbs.mc9y.net"
COOKIES_FILE=$(mktemp)
trap "rm -f $COOKIES_FILE" EXIT

log() { echo "[mc9y] $*"; }
err() { echo "[mc9y] 错误: $*" >&2; exit 1; }

# 检查凭据
[ -z "${MC9Y_USERNAME:-}" ] && err "未设置 MC9Y_USERNAME"
[ -z "${MC9Y_PASSWORD:-}" ] && err "未设置 MC9Y_PASSWORD"

# ============================================================
# 步骤 1: 获取登录页面 CSRF Token
# ============================================================
log "获取登录页面..."
LOGIN_PAGE=$(curl -s -c "$COOKIES_FILE" "$BASE_URL/login/")
XF_TOKEN=$(echo "$LOGIN_PAGE" | grep -oP 'name="_xfToken" value="\K[^"]+' | head -1)
[ -z "$XF_TOKEN" ] && err "无法获取登录页面 CSRF Token"
log "CSRF Token 获取成功"

# ============================================================
# 步骤 2: 登录
# ============================================================
log "正在登录 (用户: $MC9Y_USERNAME)..."
LOGIN_RESP=$(curl -s -c "$COOKIES_FILE" -b "$COOKIES_FILE" \
  --data-urlencode "login=$MC9Y_USERNAME" \
  --data-urlencode "password=$MC9Y_PASSWORD" \
  --data-urlencode "_xfToken=$XF_TOKEN" \
  -d "remember=1" \
  --data-urlencode "_xfRedirect=/" \
  -X POST "$BASE_URL/login/login" \
  -w "\n__HTTP_CODE__%{http_code}" \
  -L --max-redirs 5)

HTTP_CODE=$(echo "$LOGIN_RESP" | grep -oP '__HTTP_CODE__\K\d+')
RESP_BODY=$(echo "$LOGIN_RESP" | sed 's/__HTTP_CODE__.*//')

if echo "$RESP_BODY" | grep -q 'name="login".*autocomplete="username"'; then
  ERROR_MSG=$(echo "$RESP_BODY" | grep -oP 'blockMessage--error[^>]*>\K[^<]+' | head -1)
  err "登录失败: ${ERROR_MSG:-用户名或密码错误}"
fi
log "登录成功 (HTTP $HTTP_CODE)"

# ============================================================
# 步骤 3: 获取更新表单
# ============================================================
log "获取更新表单..."
UPDATE_FORM_PAGE=$(curl -s -c "$COOKIES_FILE" -b "$COOKIES_FILE" "$BASE_URL/resources/$RESOURCE_ID/post-update")

if echo "$UPDATE_FORM_PAGE" | grep -q 'data-template="login"'; then
  err "访问更新页面失败 - 仍然在登录页面"
fi

UPDATE_TOKEN=$(echo "$UPDATE_FORM_PAGE" | grep -oP 'name="_xfToken" value="\K[^"]+' | head -1)
[ -z "$UPDATE_TOKEN" ] && err "无法获取更新表单 CSRF Token"

# 提取版本附件 hash
VERSION_HASH=$(echo "$UPDATE_FORM_PAGE" | grep -oP 'name="version_attachment_hash" value="\K[^"]+' | head -1)
[ -z "$VERSION_HASH" ] && err "无法获取版本附件 hash"

# 提取更新附件 hash
UPDATE_HASH=$(echo "$UPDATE_FORM_PAGE" | grep -oP 'name="attachment_hash" value="\K[^"]+' | head -1)
log "表单 Token 和 hash 获取成功"

# ============================================================
# 步骤 4: 上传 JAR 附件 (如果提供了文件)
# XenForo 使用 Flow.js 分块上传协议
# ============================================================
ATTACHMENT_ID=""
if [ -n "$JAR_FILE" ] && [ -f "$JAR_FILE" ]; then
  JAR_FILENAME=$(basename "$JAR_FILE")
  FILE_SIZE=$(stat -c%s "$JAR_FILE" 2>/dev/null || stat -f%z "$JAR_FILE" 2>/dev/null)
  # flowIdentifier 格式: {filesize}-{filename去除点号}
  FLOW_ID="${FILE_SIZE}-$(echo "$JAR_FILENAME" | sed 's/\.//g')"
  log "上传 JAR 文件: $JAR_FILENAME ($FILE_SIZE bytes)"

  # 上传时使用原始 hash
  UPLOAD_URL="$BASE_URL/attachments/upload?type=resource_version&context%5Bresource_id%5D=$RESOURCE_ID&hash=$VERSION_HASH"

  UPLOAD_RESP=$(curl -s -c "$COOKIES_FILE" -b "$COOKIES_FILE" \
    -H "Referer: $BASE_URL/resources/$RESOURCE_ID/post-update" \
    -H "Origin: $BASE_URL" \
    -X POST "$UPLOAD_URL" \
    -F "_xfToken=$UPDATE_TOKEN" \
    -F "_xfResponseType=json" \
    -F "_xfWithData=1" \
    -F "flowChunkNumber=1" \
    -F "flowChunkSize=4294967296" \
    -F "flowCurrentChunkSize=$FILE_SIZE" \
    -F "flowTotalSize=$FILE_SIZE" \
    -F "flowIdentifier=$FLOW_ID" \
    -F "flowFilename=$JAR_FILENAME" \
    -F "flowRelativePath=$JAR_FILENAME" \
    -F "flowTotalChunks=1" \
    -F "upload=@$JAR_FILE;filename=$JAR_FILENAME;type=application/octet-stream" \
    -w "\n__HTTP_CODE__%{http_code}")

  UPLOAD_HTTP=$(echo "$UPLOAD_RESP" | grep -oP '__HTTP_CODE__\K\d+')
  UPLOAD_BODY=$(echo "$UPLOAD_RESP" | sed 's/__HTTP_CODE__.*//')

  log "上传响应 (HTTP $UPLOAD_HTTP)"

  # 检查上传结果
  if echo "$UPLOAD_BODY" | grep -q '"error"'; then
    UPLOAD_ERR=$(echo "$UPLOAD_BODY" | grep -oP '"error":"[^"]*"' | head -1)
    err "附件上传失败: $UPLOAD_ERR"
  fi

  # 提取 attachment_id
  if echo "$UPLOAD_BODY" | grep -q '"status": "ok"'; then
    ATTACHMENT_ID=$(echo "$UPLOAD_BODY" | grep -oP '"attachment_id": \K\d+' | head -1)
    log "JAR 文件上传成功 (attachment_id: $ATTACHMENT_ID)"
  else
    log "附件上传响应: $UPLOAD_BODY"
    log "附件上传状态: HTTP $UPLOAD_HTTP (继续提交...)"
  fi
else
  log "未提供 JAR 文件，跳过附件上传"
fi

# ============================================================
# 步骤 5: 重新获取表单 token (上传后 token 会变化)
# 注意: 不要更新 hash，因为附件是绑定到原始 hash 的
# ============================================================
log "刷新表单 token..."
UPDATE_FORM_PAGE=$(curl -s -c "$COOKIES_FILE" -b "$COOKIES_FILE" "$BASE_URL/resources/$RESOURCE_ID/post-update")
UPDATE_TOKEN=$(echo "$UPDATE_FORM_PAGE" | grep -oP 'name="_xfToken" value="\K[^"]+' | head -1)
[ -z "$UPDATE_TOKEN" ] && err "无法刷新表单 token"
log "表单 token 已刷新"

# ============================================================
# 步骤 6: 提交更新表单 (使用原始 hash，因为附件绑定到原始 hash)
# ============================================================
log "提交更新: v$VERSION - $TITLE"

SUBMIT_RESP=$(curl -s -c "$COOKIES_FILE" -b "$COOKIES_FILE" \
  -H "Referer: $BASE_URL/resources/$RESOURCE_ID/post-update" \
  -H "Origin: $BASE_URL" \
  -H "X-Requested-With: XMLHttpRequest" \
  --data-urlencode "_xfToken=$UPDATE_TOKEN" \
  -d "_xfResponseType=json" \
  -d "new_version=1" \
  --data-urlencode "version_string=$VERSION" \
  -d "version_type=local" \
  --data-urlencode "version_attachment_hash=$VERSION_HASH" \
  -d "new_update=1" \
  --data-urlencode "update_title=$TITLE" \
  --data-urlencode "update_message_html=$MESSAGE" \
  --data-urlencode "attachment_hash=$UPDATE_HASH" \
  -X POST "$BASE_URL/resources/$RESOURCE_ID/post-update" \
  -w "\n__HTTP_CODE__%{http_code}" \
  -L --max-redirs 5)

SUBMIT_HTTP=$(echo "$SUBMIT_RESP" | grep -oP '__HTTP_CODE__\K\d+')
SUBMIT_BODY=$(echo "$SUBMIT_RESP" | sed 's/__HTTP_CODE__.*//')

# ============================================================
# 步骤 7: 验证结果
# ============================================================
# 检查 JSON 响应
if echo "$SUBMIT_BODY" | grep -q '"status": "ok"'; then
  log "更新发布成功! v$VERSION"
  exit 0
fi

# 检查 JSON 错误响应
if echo "$SUBMIT_BODY" | grep -q '"status": "error"'; then
  ERROR_MSG=$(echo "$SUBMIT_BODY" | grep -oP '"errors": \["\K[^"]+' | head -1)
  err "更新提交失败: ${ERROR_MSG:-未知错误}"
fi

# XenForo 成功时返回 303 重定向到 updates 页面
if [ "$SUBMIT_HTTP" = "303" ]; then
  log "更新发布成功! v$VERSION (303 重定向)"
  exit 0
fi

if echo "$SUBMIT_BODY" | grep -q 'resources/.*updates\|资源已更新\|更新成功'; then
  log "更新发布成功! v$VERSION"
  exit 0
fi

if [ "$SUBMIT_HTTP" = "200" ]; then
  # 检查是否仍在更新页面（表单提交失败）
  if echo "$SUBMIT_BODY" | grep -q 'post-update'; then
    ERROR_MSG=$(echo "$SUBMIT_BODY" | grep -oP 'blockMessage--error[^>]*>\K[^<]+' | head -1)
    if [ -n "$ERROR_MSG" ]; then
      err "更新提交失败: $ERROR_MSG"
    fi
    FORM_ERRORS=$(echo "$SUBMIT_BODY" | grep -oP 'formValidationRow[^>]*>\K[^<]+' | tr '\n' ' ')
    if [ -n "$FORM_ERRORS" ]; then
      err "表单验证失败: $FORM_ERRORS"
    fi
    PAGE_TITLE=$(echo "$SUBMIT_BODY" | grep -oP '<title>\K[^<]+')
    err "更新可能失败，页面标题: $PAGE_TITLE"
  fi
  log "更新已提交 (HTTP 200)，请检查论坛确认"
  exit 0
fi

if echo "$SUBMIT_RESP" | grep -q "resources/$RESOURCE_ID"; then
  log "更新发布成功! v$VERSION"
  exit 0
fi

err "更新状态不确定 (HTTP $SUBMIT_HTTP)，请手动检查论坛"
