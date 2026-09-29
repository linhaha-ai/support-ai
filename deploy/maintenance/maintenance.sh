#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly BACKUP_ROOT="/backups"
readonly DATA_ROOT="/data"
readonly LIVE_STORAGE_DIR="${DATA_ROOT}/storage"
readonly STORAGE_UID="10001"
readonly STORAGE_GID="10001"

operation_lock=""

log() {
    printf '[maintenance] %s\n' "$*"
}

fail() {
    printf '[maintenance] 错误：%s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "缺少必需命令：$1"
}

require_environment() {
    local variable_name
    for variable_name in PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD; do
        [[ -n "${!variable_name:-}" ]] || fail "缺少必需环境变量：${variable_name}"
    done

    case "${PGDATABASE}" in
        postgres | template0 | template1)
            fail "禁止对 PostgreSQL 系统数据库执行维护操作"
            ;;
    esac
}

validate_backup_name() {
    local backup_name="$1"
    local windows_stem

    [[ "${backup_name}" =~ ^[A-Za-z0-9]([A-Za-z0-9._-]{0,62}[A-Za-z0-9_-])?$ ]] \
        || fail "备份名只能包含字母、数字、点、下划线和连字符，长度为 1 到 64，且不能以点结尾"

    # 备份目录位于宿主机绑定目录，需同时规避 Windows 保留文件名。
    windows_stem="${backup_name%%.*}"
    windows_stem="${windows_stem^^}"
    case "${windows_stem}" in
        CON | PRN | AUX | NUL | COM[1-9] | LPT[1-9])
            fail "备份名不能使用 Windows 保留文件名"
            ;;
    esac
}

assert_direct_child() {
    local parent="$1"
    local child="$2"
    local prefix="${parent}/"
    local relative_path

    [[ "${child}" == "${prefix}"* ]] || fail "路径越过允许边界：${child}"
    relative_path="${child#"${prefix}"}"
    [[ -n "${relative_path}" && "${relative_path}" != */* ]] \
        || fail "路径不是允许目录的直接子项：${child}"
}

acquire_operation_lock() {
    operation_lock="${DATA_ROOT}/.maintenance.lock"
    if ! mkdir -- "${operation_lock}" 2>/dev/null; then
        fail "已有备份或恢复操作正在使用文档卷；若上次进程被强制终止，请人工核对后清理 ${operation_lock}"
    fi
    if ! printf '%s\n' "$$" > "${operation_lock}/pid"; then
        rmdir -- "${operation_lock}" 2>/dev/null || true
        operation_lock=""
        fail "无法写入文档卷维护锁"
    fi
}

release_operation_lock() {
    if [[ -n "${operation_lock}" && -d "${operation_lock}" ]]; then
        rm -f -- "${operation_lock}/pid"
        rmdir -- "${operation_lock}" 2>/dev/null || true
    fi
    operation_lock=""
}

validate_backup_files() {
    local backup_name="$1"
    local backup_dir="$2"
    local actual_files
    local expected_files
    local manifest_lines=()
    local required_file

    [[ -d "${backup_dir}" && ! -L "${backup_dir}" ]] || fail "备份目录不存在或不是普通目录：${backup_name}"
    assert_direct_child "${BACKUP_ROOT}" "${backup_dir}"

    actual_files="$(find "${backup_dir}" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort)"
    expected_files="$(printf '%s\n' MANIFEST SHA256SUMS database.dump storage.tar | LC_ALL=C sort)"
    [[ "${actual_files}" == "${expected_files}" ]] || fail "备份文件清单不完整或包含未声明文件"

    for required_file in MANIFEST SHA256SUMS database.dump storage.tar; do
        [[ -f "${backup_dir}/${required_file}" && ! -L "${backup_dir}/${required_file}" ]] \
            || fail "备份文件不是普通文件：${required_file}"
    done

    mapfile -t manifest_lines < "${backup_dir}/MANIFEST"
    [[ "${#manifest_lines[@]}" -eq 6 ]] || fail "MANIFEST 行数不符合预期"
    [[ "${manifest_lines[0]}" == "format_version=1" ]] || fail "不支持的备份格式版本"
    [[ "${manifest_lines[1]}" == "backup_name=${backup_name}" ]] || fail "MANIFEST 备份名不匹配"
    [[ "${manifest_lines[2]}" =~ ^created_at_utc=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
        || fail "MANIFEST 创建时间格式无效"
    [[ "${manifest_lines[3]}" =~ ^flyway_version=[0-9]+([.][0-9]+)*$ ]] \
        || fail "MANIFEST Flyway 版本无效"
    [[ "${manifest_lines[4]}" == "database_archive=database.dump" ]] || fail "MANIFEST 数据库归档声明无效"
    [[ "${manifest_lines[5]}" == "storage_archive=storage.tar" ]] || fail "MANIFEST 文件归档声明无效"

    awk '
        BEGIN {
            expected[1] = "database.dump"
            expected[2] = "storage.tar"
            expected[3] = "MANIFEST"
        }
        NF != 2 || length($1) != 64 || $1 ~ /[^0-9a-f]/ || $2 != expected[NR] {
            exit 1
        }
        END {
            if (NR != 3) {
                exit 1
            }
        }
    ' "${backup_dir}/SHA256SUMS" || fail "SHA256SUMS 格式或文件清单无效"

    (
        cd -- "${backup_dir}"
        sha256sum --check --strict SHA256SUMS
    ) || fail "备份文件摘要校验失败"

    pg_restore --list "${backup_dir}/database.dump" >/dev/null \
        || fail "database.dump 不是有效的 PostgreSQL 自定义格式归档"
}

validate_storage_archive() {
    local archive_path="$1"
    local list_file="$2"
    local verbose_file="$3"
    local entry
    local normalized_entry
    local entry_type

    tar --list --file "${archive_path}" > "${list_file}" || fail "无法读取 storage.tar 文件清单"
    tar --list --verbose --file "${archive_path}" > "${verbose_file}" || fail "无法读取 storage.tar 条目类型"

    # 仅允许 LocalFileStore 生成的目录层级和文件名，拒绝穿越路径与特殊条目。
    while IFS= read -r entry; do
        normalized_entry="${entry#./}"
        normalized_entry="${normalized_entry%/}"
        if [[ -z "${normalized_entry}" || "${normalized_entry}" == "." ]]; then
            continue
        fi
        if [[ "${entry}" == */ ]]; then
            [[ "${normalized_entry}" =~ ^[0-9]{4}(/[0-9]{2})?$ ]] \
                || fail "storage.tar 包含非法目录路径：${entry}"
        else
            [[ "${normalized_entry}" =~ ^[0-9]{4}/[0-9]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{1,16}$ ]] \
                || fail "storage.tar 包含非法文件路径：${entry}"
        fi
    done < "${list_file}"

    while IFS= read -r entry; do
        entry_type="${entry:0:1}"
        case "${entry_type}" in
            - | d) ;;
            *) fail "storage.tar 包含符号链接、硬链接或设备文件" ;;
        esac
    done < "${verbose_file}"
}

validate_restored_documents() {
    local temporary_database="$1"
    local staging_dir="$2"
    local rows_file="$3"
    local expected_flyway_version="$4"
    local storage_key
    local expected_hash
    local expected_size
    local stored_file
    local actual_hash
    local actual_size
    local pointer_conflicts
    local restored_flyway_version

    restored_flyway_version="$(psql --dbname="${temporary_database}" --no-align --tuples-only \
        --set=ON_ERROR_STOP=1 --command="
            SELECT version
            FROM flyway_schema_history
            WHERE success = TRUE
            ORDER BY installed_rank DESC
            LIMIT 1
        ")"
    [[ "${restored_flyway_version}" == "${expected_flyway_version}" ]] \
        || fail "临时数据库 Flyway 版本与 MANIFEST 不一致"

    # document 与 document_version：未删除文档的全部版本都必须有可验证的原文件。
    psql --dbname="${temporary_database}" --no-align --tuples-only --field-separator=$'\t' \
        --set=ON_ERROR_STOP=1 --command="
            SELECT version.storage_key, version.file_hash, version.file_size
            FROM document_version AS version
            INNER JOIN document AS document ON document.id = version.document_id
            WHERE document.deleted_at IS NULL
            ORDER BY version.storage_key
        " > "${rows_file}"

    while IFS=$'\t' read -r storage_key expected_hash expected_size; do
        [[ -n "${storage_key}" ]] || continue
        [[ "${storage_key}" =~ ^[0-9]{4}/[0-9]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{1,16}$ ]] \
            || fail "document_version.storage_key 不符合受控路径规则"
        [[ "${expected_hash}" =~ ^[0-9a-f]{64}$ ]] || fail "document_version.file_hash 格式无效"
        [[ "${expected_size}" =~ ^[0-9]+$ ]] || fail "document_version.file_size 格式无效"

        stored_file="${staging_dir}/${storage_key}"
        [[ -f "${stored_file}" && ! -L "${stored_file}" ]] || fail "备份缺少未删除文档的原文件：${storage_key}"

        actual_size="$(stat --format='%s' -- "${stored_file}")"
        [[ "${actual_size}" == "${expected_size}" ]] || fail "原文件大小校验失败：${storage_key}"

        actual_hash="$(sha256sum -- "${stored_file}")"
        actual_hash="${actual_hash%% *}"
        [[ "${actual_hash}" == "${expected_hash}" ]] || fail "原文件摘要校验失败：${storage_key}"
    done < "${rows_file}"

    # document 与 document_version：活动版本必须 READY，待处理版本只能 QUEUED 或 BUILDING。
    pointer_conflicts="$(psql --dbname="${temporary_database}" --no-align --tuples-only \
        --set=ON_ERROR_STOP=1 --command="
            SELECT COUNT(*)
            FROM document AS document
            LEFT JOIN document_version AS active_version
                ON active_version.id = document.active_version_id
               AND active_version.document_id = document.id
            LEFT JOIN document_version AS pending_version
                ON pending_version.id = document.pending_version_id
               AND pending_version.document_id = document.id
            WHERE document.deleted_at IS NULL
              AND (
                  (document.active_version_id IS NOT NULL
                      AND (active_version.id IS NULL OR active_version.status <> 'READY'))
                  OR
                  (document.pending_version_id IS NOT NULL
                      AND (pending_version.id IS NULL OR pending_version.status NOT IN ('QUEUED', 'BUILDING')))
              )
        ")"
    [[ "${pointer_conflicts}" == "0" ]] || fail "文档活动或待处理版本指针存在状态冲突"
}

create_backup() {
    local backup_name="$1"
    local backup_dir="${BACKUP_ROOT}/${backup_name}"
    local temporary_dir=""
    local tar_list_file=""
    local tar_verbose_file=""
    local created_at_utc
    local flyway_version

    assert_direct_child "${BACKUP_ROOT}" "${backup_dir}"
    [[ ! -e "${backup_dir}" && ! -L "${backup_dir}" ]] || fail "同名备份已存在：${backup_name}"
    [[ -d "${LIVE_STORAGE_DIR}" && ! -L "${LIVE_STORAGE_DIR}" ]] || fail "文档存储目录不存在：${LIVE_STORAGE_DIR}"

    cleanup_backup() {
        local exit_code=$?
        trap - EXIT INT TERM
        if [[ -n "${temporary_dir}" && -d "${temporary_dir}" ]]; then
            rm -rf -- "${temporary_dir}"
        fi
        [[ -n "${tar_list_file}" ]] && rm -f -- "${tar_list_file}"
        [[ -n "${tar_verbose_file}" ]] && rm -f -- "${tar_verbose_file}"
        release_operation_lock
        exit "${exit_code}"
    }

    acquire_operation_lock
    trap cleanup_backup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    temporary_dir="$(mktemp -d "${BACKUP_ROOT}/.${backup_name}.tmp.XXXXXX")"
    tar_list_file="$(mktemp "/tmp/support-ai-backup-tar-list.XXXXXX")"
    tar_verbose_file="$(mktemp "/tmp/support-ai-backup-tar-verbose.XXXXXX")"
    assert_direct_child "${BACKUP_ROOT}" "${temporary_dir}"

    log "正在导出数据库快照"
    pg_dump --dbname="${PGDATABASE}" --format=custom --compress=9 \
        --no-owner --no-privileges \
        --file="${temporary_dir}/database.dump"

    log "正在归档文档原文件"
    # .tmp 只保存上传中间文件，不能进入业务备份或恢复校验。
    tar --create --file "${temporary_dir}/storage.tar" \
        --exclude='./.tmp' --exclude='./.tmp/*' \
        --directory "${LIVE_STORAGE_DIR}" .

    created_at_utc="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    flyway_version="$(psql --dbname="${PGDATABASE}" --no-align --tuples-only \
        --set=ON_ERROR_STOP=1 --command="
            SELECT version
            FROM flyway_schema_history
            WHERE success = TRUE
            ORDER BY installed_rank DESC
            LIMIT 1
        ")"
    [[ "${flyway_version}" =~ ^[0-9]+([.][0-9]+)*$ ]] || fail "无法读取有效的 Flyway 版本"
    printf '%s\n' \
        'format_version=1' \
        "backup_name=${backup_name}" \
        "created_at_utc=${created_at_utc}" \
        "flyway_version=${flyway_version}" \
        'database_archive=database.dump' \
        'storage_archive=storage.tar' \
        > "${temporary_dir}/MANIFEST"

    (
        cd -- "${temporary_dir}"
        sha256sum database.dump storage.tar MANIFEST > SHA256SUMS
    )

    validate_backup_files "${backup_name}" "${temporary_dir}"
    validate_storage_archive "${temporary_dir}/storage.tar" "${tar_list_file}" "${tar_verbose_file}"
    mv -- "${temporary_dir}" "${backup_dir}"
    temporary_dir=""
    log "备份已完成：${backup_name}"
    cleanup_backup
}

restore_backup() {
    local backup_name="$1"
    local backup_dir="${BACKUP_ROOT}/${backup_name}"
    local temporary_database=""
    local staging_dir=""
    local old_storage_dir="${DATA_ROOT}/.restore-old-${backup_name}-$$"
    local rows_file=""
    local tar_list_file=""
    local tar_verbose_file=""
    local old_storage_moved=0
    local new_storage_moved=0
    local database_committed=0
    local expected_flyway_version

    assert_direct_child "${BACKUP_ROOT}" "${backup_dir}"
    assert_direct_child "${DATA_ROOT}" "${old_storage_dir}"
    [[ "$(id -u)" == "0" ]] || fail "恢复操作必须以 root 身份运行，以便修正文档目录所有权"
    [[ ! -e "${old_storage_dir}" && ! -L "${old_storage_dir}" ]] || fail "恢复回滚目录已存在"

    cleanup_restore() {
        local exit_code=$?
        local rollback_ok=1
        trap - EXIT INT TERM
        set +e

        if [[ -n "${temporary_database}" ]]; then
            dropdb --maintenance-db=postgres --if-exists --force -- "${temporary_database}" >/dev/null 2>&1
        fi

        # pg_restore 使用单事务；提交前失败时，将文件目录恢复到操作前状态。
        if [[ "${database_committed}" -eq 0 ]]; then
            if [[ "${new_storage_moved}" -eq 1 && -d "${LIVE_STORAGE_DIR}" && ! -e "${staging_dir}" ]]; then
                mv -- "${LIVE_STORAGE_DIR}" "${staging_dir}" || rollback_ok=0
            fi
            if [[ "${old_storage_moved}" -eq 1 && -d "${old_storage_dir}" && ! -e "${LIVE_STORAGE_DIR}" ]]; then
                mv -- "${old_storage_dir}" "${LIVE_STORAGE_DIR}" || rollback_ok=0
            fi
        fi

        if [[ "${rollback_ok}" -eq 1 && -n "${staging_dir}" && -d "${staging_dir}" ]]; then
            rm -rf -- "${staging_dir}"
        fi
        [[ -n "${rows_file}" ]] && rm -f -- "${rows_file}"
        [[ -n "${tar_list_file}" ]] && rm -f -- "${tar_list_file}"
        [[ -n "${tar_verbose_file}" ]] && rm -f -- "${tar_verbose_file}"
        release_operation_lock

        if [[ "${rollback_ok}" -ne 1 ]]; then
            printf '[maintenance] 错误：恢复失败且文件目录自动回滚未完成，请保持应用停止并人工核对 /data\n' >&2
        fi
        exit "${exit_code}"
    }

    acquire_operation_lock
    trap cleanup_restore EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    validate_backup_files "${backup_name}" "${backup_dir}"
    expected_flyway_version="$(sed -n '4s/^flyway_version=//p' "${backup_dir}/MANIFEST")"

    staging_dir="$(mktemp -d "${DATA_ROOT}/.restore-${backup_name}.XXXXXX")"
    rows_file="$(mktemp "/tmp/support-ai-restore-rows.XXXXXX")"
    tar_list_file="$(mktemp "/tmp/support-ai-restore-tar-list.XXXXXX")"
    tar_verbose_file="$(mktemp "/tmp/support-ai-restore-tar-verbose.XXXXXX")"
    assert_direct_child "${DATA_ROOT}" "${staging_dir}"

    validate_storage_archive "${backup_dir}/storage.tar" "${tar_list_file}" "${tar_verbose_file}"
    tar --extract --file "${backup_dir}/storage.tar" --directory "${staging_dir}" \
        --no-same-owner --no-same-permissions

    temporary_database="supportai_restore_$$_$(date -u '+%s')"
    log "正在临时数据库中验证备份"
    createdb --maintenance-db=postgres -- "${temporary_database}"
    pg_restore --exit-on-error --no-owner --no-privileges \
        --dbname="${temporary_database}" "${backup_dir}/database.dump"
    validate_restored_documents "${temporary_database}" "${staging_dir}" "${rows_file}" \
        "${expected_flyway_version}"
    dropdb --maintenance-db=postgres --force -- "${temporary_database}"
    temporary_database=""

    chown -R "${STORAGE_UID}:${STORAGE_GID}" "${staging_dir}"

    # 暂存目录与正式目录位于同一卷，rename 不会产生跨文件系统复制窗口。
    if [[ -e "${LIVE_STORAGE_DIR}" || -L "${LIVE_STORAGE_DIR}" ]]; then
        [[ -d "${LIVE_STORAGE_DIR}" && ! -L "${LIVE_STORAGE_DIR}" ]] || fail "当前文档存储路径不是普通目录"
        mv -- "${LIVE_STORAGE_DIR}" "${old_storage_dir}"
        old_storage_moved=1
    fi
    mv -- "${staging_dir}" "${LIVE_STORAGE_DIR}"
    new_storage_moved=1

    log "正在以单事务恢复目标数据库"
    psql --dbname=postgres --set=ON_ERROR_STOP=1 --set=target_database="${PGDATABASE}" >/dev/null <<'SQL'
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = :'target_database'
  AND pid <> pg_backend_pid();
SQL
    pg_restore --clean --if-exists --exit-on-error --single-transaction \
        --no-owner --no-privileges --dbname="${PGDATABASE}" "${backup_dir}/database.dump"
    database_committed=1

    if [[ "${old_storage_moved}" -eq 1 ]]; then
        rm -rf -- "${old_storage_dir}"
        old_storage_moved=0
    fi
    staging_dir=""
    new_storage_moved=0
    log "恢复已完成：${backup_name}"
    cleanup_restore
}

main() {
    local command_name="${1:-}"
    local backup_name="${2:-}"

    [[ "$#" -eq 2 ]] || fail "用法：maintenance.sh <backup|restore> <backup-name>"
    validate_backup_name "${backup_name}"
    require_environment

    require_command find
    require_command pg_dump
    require_command pg_restore
    require_command psql
    require_command sha256sum
    require_command sed
    require_command tar

    mkdir -p -- "${BACKUP_ROOT}" "${DATA_ROOT}"
    case "${command_name}" in
        backup)
            create_backup "${backup_name}"
            ;;
        restore)
            require_command chown
            require_command createdb
            require_command dropdb
            require_command stat
            restore_backup "${backup_name}"
            ;;
        *)
            fail "未知维护命令：${command_name}"
            ;;
    esac
}

main "$@"
