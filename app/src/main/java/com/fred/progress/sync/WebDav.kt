package com.fred.progress.sync

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 极简 WebDAV 客户端：只做同步需要的两件事（GET 拉 / PUT 推）。
 *
 * 用 HttpURLConnection 手写，不引 OkHttp —— 本项目零第三方依赖。
 * 代价：只支持 GET / PUT，云端目录 Yiju 要**先存在**（坚果云网页端建好即可）。
 *
 * 认证用 Basic：坚果云要用**应用密码**，不是登录密码。
 */
internal class WebDav(
    private val fileUrl: String,
    private val user: String,
    private val pass: String
) {

    data class Fetched(val exists: Boolean, val content: String?)

    /** 拉远端文件。404 = 云端还没有这个文件。 */
    fun fetch(): Fetched {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            // 同步场景绝不能用缓存：坚果云响应允许短暂缓存，一旦拿到旧内容，
            // 本地会以为云端落后而把旧数据写回去。所以 useCaches=false + no-cache。
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("Authorization", auth())
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            val code = c.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> Fetched(false, null)
                code !in 200..299 -> throw IOException("GET $code")
                else -> Fetched(true, c.inputStream.readBytes().toString(Charset.forName("UTF-8")))
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * 推上去，**返回真实 HTTP 状态码**，交给调用方显示。
     *
     * 为什么不用 ETag / If-Match / 重试：
     * 坚果云的 ETag 实测不可信 —— GET 刚拿到的值立刻 PUT 会被判 412
     * "ETag doesn't match"，重试也一样撞（缓存、代理、多路径下值会对不上）。
     * 死守乐观锁就永远写不进去。合并本身幂等、以最后更新为准，
     * 所以干脆一次 GET +（必要时）一次 PUT，不带 If-Match、不重试，最坏只是
     * "最后写入者胜"，不会丢数据。
     */
    fun put(content: String): Int {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Authorization", auth())
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT

            c.outputStream.use { it.write(content.toByteArray(Charset.forName("UTF-8"))) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun auth(): String = "Basic " + Base64.encodeToString(
        "$user:$pass".toByteArray(Charset.forName("UTF-8")),
        Base64.NO_WRAP
    )

    companion object {
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
    }
}
