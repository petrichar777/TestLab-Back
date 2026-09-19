#!/bin/bash
#
# 考试网站后端 —— 自动部署脚本
#
# 由 GitHub Actions 通过 SSH 调用；也可以在服务器上手动执行：
#   bash /www/deploy/exam-backend/deploy.sh
#
# 流程：备份当前 jar -> 原子替换 -> 调用宝塔重启 -> 健康检查 -> 失败自动回滚
#
set -euo pipefail

# ---------------- 配置 ----------------
APP_DIR="/www/wwwroot/考试网站"                        # 宝塔 Java 项目的目录（进程 CWD 就是这里）
JAR_NAME="exam-backend-0.1.0.jar"
STAGING_DIR="/www/deploy/exam-backend"                 # 新 jar 的中转目录
BT_PROJECT="考试"                                      # 宝塔「Java项目」里的项目名
HEALTH_URL="http://127.0.0.1:8099/api/papers"          # permitAll 且会查库，能同时验证 Web 层与数据库
HEALTH_RETRIES=30                                      # 最多尝试 30 次
HEALTH_INTERVAL=2                                      # 每次间隔 2 秒，即最长等待 60 秒
KEEP_BACKUPS=3                                         # 保留最近 3 个备份

JAR="${APP_DIR}/${JAR_NAME}"
NEW_JAR="${STAGING_DIR}/app.jar"
BACKUP_DIR="${APP_DIR}/backups"

log()  { echo "[deploy $(date '+%F %T')] $*"; }
fail() { log "错误：$*"; exit 1; }

# ---------------- 函数 ----------------
restart_app() {
    log "调用宝塔重启项目「${BT_PROJECT}」"
    btpython /www/server/panel/script/restart_project.py java "${BT_PROJECT}"
}

health_check() {
    local i
    for i in $(seq 1 "${HEALTH_RETRIES}"); do
        if curl -fsS -m 3 -o /dev/null "${HEALTH_URL}"; then
            log "健康检查通过（第 ${i} 次尝试）"
            return 0
        fi
        sleep "${HEALTH_INTERVAL}"
    done
    return 1
}

# ---------------- 0. 前置检查 ----------------
[ -f "${NEW_JAR}" ] || fail "找不到待部署的 jar：${NEW_JAR}"
[ -f "${APP_DIR}/config/application.yml" ] \
    || fail "缺少 ${APP_DIR}/config/application.yml。新版 jar 不再内嵌配置，没有它服务无法启动"

# ---------------- 1. 备份 ----------------
mkdir -p "${BACKUP_DIR}"
if [ -f "${JAR}" ]; then
    BACKUP="${BACKUP_DIR}/${JAR_NAME}.$(date '+%Y%m%d%H%M%S')"
    cp -p "${JAR}" "${BACKUP}"
    log "已备份当前 jar -> ${BACKUP}"

    # 只保留最近 KEEP_BACKUPS 个备份
    mapfile -t OLD_BACKUPS < <(ls -1t "${BACKUP_DIR}/${JAR_NAME}."* 2>/dev/null || true)
    if [ "${#OLD_BACKUPS[@]}" -gt "${KEEP_BACKUPS}" ]; then
        printf '%s\n' "${OLD_BACKUPS[@]:${KEEP_BACKUPS}}" | xargs -r rm -f
        log "已清理旧备份，保留最近 ${KEEP_BACKUPS} 个"
    fi
fi

# ---------------- 2. 原子替换 ----------------
# 先复制到同目录的临时文件，再用 mv 重命名。同一文件系统内的 mv 是原子操作，
# 不会出现「jar 只写了一半就被读取」的情况。
cp -f "${NEW_JAR}" "${JAR}.new"
chown www:www "${JAR}.new"
chmod 755 "${JAR}.new"
mv -f "${JAR}.new" "${JAR}"
log "已替换 jar"

# ---------------- 3. 重启 ----------------
restart_app
sleep 3

# ---------------- 4. 健康检查，失败则回滚 ----------------
if health_check; then
    log "部署成功"
    exit 0
fi

log "健康检查未通过，开始回滚"
LATEST_BACKUP="$(ls -1t "${BACKUP_DIR}/${JAR_NAME}."* 2>/dev/null | head -1 || true)"
[ -n "${LATEST_BACKUP}" ] || fail "没有可用备份，无法回滚，请人工介入"

cp -p "${LATEST_BACKUP}" "${JAR}"
chown www:www "${JAR}"
restart_app
log "已回滚到 ${LATEST_BACKUP}，本次部署失败"
exit 1