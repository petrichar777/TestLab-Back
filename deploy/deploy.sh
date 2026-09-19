#!/bin/bash
#
# 考试网站后端 —— 自动部署脚本
#
# 由 GitHub Actions 通过 SSH 调用；也可以在服务器上手动执行：
#   bash /www/deploy/exam-backend/deploy.sh
#
# 流程：备份当前 jar -> 原子替换 -> 重启 -> 健康检查 -> 失败自动回滚
#
# 为什么不用 `btpython restart_project.py`：实测该入口会打印「重启成功」但进程根本不起来。
# 原因是 /var/tmp/springboot/vhost/pids 属主为 root、权限 755，而宝塔以 www 身份执行启动
# 脚本，脚本末尾的 `echo $! > pid文件` 会 Permission denied；宝塔随后判定启动失败并清理进程。
# 因此这里改为自行停启（启动参数照抄宝塔生成脚本），并由 root 回写 pid 文件。
#
set -euo pipefail

# ---------------- 配置 ----------------
APP_DIR="/www/wwwroot/考试网站"                        # 宝塔 Java 项目目录（进程 CWD 就是这里）
JAR_NAME="exam-backend-0.1.0.jar"
STAGING_DIR="/www/deploy/exam-backend"                 # 新 jar 的中转目录
BT_PROJECT="考试"                                      # 宝塔「Java项目」里的项目名

JAVA="/www/server/java/jdk-17.0.8/bin/java"
JVM_ARGS="-Xmx1024M -Xms256M"
RUN_USER="www"
LOG_DIR="/www/wwwlogs/java/springboot"                 # 与宝塔一致的日志目录
LOG_FILE="${LOG_DIR}/${BT_PROJECT}.log"
PID_FILE="/var/tmp/springboot/vhost/pids/${BT_PROJECT}.pid"
ENV_FILE="/var/tmp/springboot/vhost/env/${BT_PROJECT}.env"

HEALTH_URL="http://127.0.0.1:8099/api/papers"          # permitAll 且会查库，能同时验证 Web 层与数据库
HEALTH_RETRIES=30                                      # 最多尝试 30 次
HEALTH_INTERVAL=2                                      # 每次间隔 2 秒。应用启动约需 15 秒，故最长等待 60 秒
KEEP_BACKUPS=3                                         # 保留最近 3 个备份

# 锚定 java 可执行文件路径，避免误匹配到「命令行里恰好含 jar 名」的 bash 包装进程
PROC_PATTERN="^${JAVA} .*${JAR_NAME}"

JAR="${APP_DIR}/${JAR_NAME}"
NEW_JAR="${STAGING_DIR}/app.jar"
BACKUP_DIR="${APP_DIR}/backups"

log()  { echo "[deploy $(date '+%F %T')] $*"; }
fail() { log "错误：$*"; exit 1; }

# ---------------- 选择 UTF-8 locale ----------------
# java 用 sun.jnu.encoding 解码命令行里的文件路径，而该值取自 LANG/LC_ALL。
# GitHub Actions 的 ssh 会话默认不传递 locale，LANG 为空时 JVM 会退化成 ASCII，
# 于是中文目录被逐字节解码成 "?"，最终报错：
#   Error: An unexpected error occurred while trying to open file
#          /www/wwwroot/????????????/exam-backend-0.1.0.jar
# 手动在交互式终端执行时 LANG 有值，所以不会复现。这里显式选一个系统可用的
# UTF-8 locale，启动时导出给子进程。
UTF8_LOCALE=""
for candidate in zh_CN.UTF-8 en_US.UTF-8 C.UTF-8 C.utf8; do
    if locale -a 2>/dev/null | grep -qix "${candidate}"; then
        UTF8_LOCALE="${candidate}"
        break
    fi
done
[ -n "${UTF8_LOCALE}" ] || fail "系统上找不到可用的 UTF-8 locale，无法以中文路径启动 java"

# ---------------- 停止 ----------------
stop_app() {
    if pgrep -u "${RUN_USER}" -f "${PROC_PATTERN}" >/dev/null 2>&1; then
        log "停止进程：$(pgrep -u "${RUN_USER}" -f "${PROC_PATTERN}" | tr '\n' ' ')"
        pkill -u "${RUN_USER}" -f "${PROC_PATTERN}" || true

        local i
        for i in $(seq 1 20); do
            pgrep -u "${RUN_USER}" -f "${PROC_PATTERN}" >/dev/null 2>&1 || break
            sleep 1
        done

        if pgrep -u "${RUN_USER}" -f "${PROC_PATTERN}" >/dev/null 2>&1; then
            log "进程 20 秒内未退出，强制结束"
            pkill -9 -u "${RUN_USER}" -f "${PROC_PATTERN}" || true
            sleep 2
        fi
        log "已停止"
    else
        log "未发现正在运行的进程"
    fi
    rm -f "${PID_FILE}"
}

# ---------------- 启动 ----------------
start_app() {
    mkdir -p "${LOG_DIR}"
    mkdir -p "$(dirname "${PID_FILE}")"

    log "启动应用（用户 ${RUN_USER}，locale ${UTF8_LOCALE}，日志 ${LOG_FILE}）"
    su -s /bin/bash -c "
        export LANG='${UTF8_LOCALE}' LC_ALL='${UTF8_LOCALE}'
        cd '${APP_DIR}' || exit 1
        [ -f '${ENV_FILE}' ] && . '${ENV_FILE}'
        nohup '${JAVA}' -jar ${JVM_ARGS} '${JAR}' >> '${LOG_FILE}' 2>&1 &
    " "${RUN_USER}"

    # pid 文件刻意由 root 回写，而不是在 su 会话里用 $! 写：
    # /var/tmp/springboot/vhost/pids 属主是 root、权限 755，www 用户写不进去。
    # 宝塔自身的重启恰好卡在这一步，导致 java 起来了却因 pid 文件为空被判为失败并清理掉。
    sleep 2
    local pid
    pid="$(pgrep -u "${RUN_USER}" -f "${PROC_PATTERN}" | head -1 || true)"
    if [ -n "${pid}" ]; then
        echo "${pid}" > "${PID_FILE}"
        chown "${RUN_USER}:${RUN_USER}" "${PID_FILE}" 2>/dev/null || true
        log "已启动，PID ${pid}"
    else
        log "警告：未发现运行中的进程"
    fi
}

restart_app() {
    stop_app
    start_app
}

# ---------------- 健康检查 ----------------
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

dump_log() {
    log "—— 应用日志最后 30 行 ——"
    tail -30 "${LOG_FILE}" 2>/dev/null || log "（读不到 ${LOG_FILE}）"
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
chown "${RUN_USER}:${RUN_USER}" "${JAR}.new"
chmod 755 "${JAR}.new"
mv -f "${JAR}.new" "${JAR}"
log "已替换 jar"

# ---------------- 3. 重启 ----------------
restart_app

# ---------------- 4. 健康检查，失败则回滚 ----------------
if health_check; then
    log "部署成功"
    exit 0
fi

log "健康检查未通过，开始回滚"
dump_log

LATEST_BACKUP="$(ls -1t "${BACKUP_DIR}/${JAR_NAME}."* 2>/dev/null | head -1 || true)"
[ -n "${LATEST_BACKUP}" ] || fail "没有可用备份，无法回滚，请人工介入"

cp -p "${LATEST_BACKUP}" "${JAR}"
chown "${RUN_USER}:${RUN_USER}" "${JAR}"
restart_app
log "已回滚到 ${LATEST_BACKUP}，本次部署失败"
exit 1