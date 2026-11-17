package io.github.kkkhs.pocketrun

object BackendLaunchCommand {
    const val MAIN_CLASS = "io.github.kkkhs.pocketrun.PrivilegedBackendMain"
    const val STARTED_MARKER = "pocketrun-backend-launched"

    fun build(apkPath: String, token: String, authority: String, appUid: Int, userId: Int): String {
        require(apkPath.startsWith('/')) { "APK 路径无效。" }
        require(TOKEN.matches(token)) { "后端启动 token 无效。" }
        require(AUTHORITY.matches(authority)) { "Binder exchange authority 无效。" }
        require(appUid > 0 && userId >= 0) { "后端启动身份无效。" }

        val arguments = listOf(
            MAIN_CLASS,
            "--token", token,
            "--authority", authority,
            "--app-uid", appUid.toString(),
            "--user", userId.toString(),
        ).joinToString(" ") { shellQuote(it) }
        return "export CLASSPATH=${shellQuote(apkPath)}; " +
            "( exec /system/bin/setsid /system/bin/app_process /system/bin " +
            "--nice-name=pocketrun:backend $arguments ) " +
            "</dev/null >/dev/null 2>&1 & backend_pid=\$!; sleep 1; " +
            "if kill -0 \"\$backend_pid\" 2>/dev/null; then " +
            "printf '${STARTED_MARKER}\\n'; else wait \"\$backend_pid\"; " +
            "printf 'pocketrun-backend-exited:%s\\n' \"\$?\"; fi"
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\"'\"'")}'"

    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    private val AUTHORITY = Regex("[A-Za-z0-9_.]+")
}
