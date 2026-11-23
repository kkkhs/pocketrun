package io.github.kkkhs.pocketrun

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

internal object LinuxRuntimeManifest {
    fun read(input: InputStream): LinuxRuntime {
        val bytes = input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024) { "Linux runtime manifest 过大。" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("schemaVersion") == 1 && json.getString("abi") == "arm64-v8a" &&
            json.getString("version") == "alpine-3.22.8-minimal-proot-5.1.107.102-candidate2-prebound" &&
            json.getString("variant") == "minimal-busybox-musl") { "Linux 固定 runtime 版本无效。" }
        val artifactsJson = json.getJSONArray("artifacts")
        require(artifactsJson.length() in 4..19)
        val artifacts = (0 until artifactsJson.length()).map { index ->
            artifactsJson.getJSONObject(index).let { LinuxArtifact(it.getString("name"), it.getString("sha256")) }
        }
        val bindingsJson = json.getJSONArray("guestBindings")
        require(bindingsJson.length() in 2..17)
        val bindings = (0 until bindingsJson.length()).map { index ->
            val item = bindingsJson.getJSONObject(index)
            val name = item.getString("apk_basename")
            require(artifacts.any { it.name == name && it.sha256 == item.getString("sha256") })
            LinuxBinding(name, item.getString("guest_path"))
        }
        val data = json.getJSONObject("dataAsset")
        require(data.getString("name") == "linux/alpine-data.bin") // Opaque AAPT name; bytes remain the pinned gzip tar.
        require(data.getString("sha256") == "8c4ac18ae5babcbd3f692e1c4bed6710eb965967da792fc6ad597def4a1eff06")
        return LinuxRuntime(RootfsInstaller.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            artifacts, bindings, data.getString("name"), data.getString("sha256"))
    }
}
