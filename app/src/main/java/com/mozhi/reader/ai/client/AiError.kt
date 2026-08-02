package com.mozhi.reader.ai.client

import java.io.IOException
import java.net.SocketTimeoutException

/** User-facing failures, all with actionable Chinese messages. */
sealed class AiClientException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class NotConfigured(roleLabel: String) :
        AiClientException("尚未配置$roleLabel，请到 设置 → AI 服务 添加并分配一个 Provider")

    class MissingKey(providerName: String) :
        AiClientException("Provider「$providerName」还没有保存 API Key")

    class InvalidKey : AiClientException("API Key 无效或没有权限（401）")

    class RateLimited : AiClientException("请求过于频繁或额度不足（429），请稍后再试")

    class Timeout : AiClientException("请求超时，请检查网络或稍后再试")

    class Network : AiClientException("网络不可用或服务地址无法访问")

    class Cancelled : AiClientException("已停止生成")

    class Unsupported(detail: String) : AiClientException(detail)

    class Http(code: Int, detail: String?) : AiClientException(
        if (detail.isNullOrBlank()) "服务返回错误（HTTP $code）" else "服务返回错误（HTTP $code）：$detail"
    )

    class Empty : AiClientException("服务没有返回内容，请重试")

    class Malformed(detail: String) : AiClientException("服务响应格式异常：$detail")
}

internal fun mapTransportError(error: Throwable): AiClientException = when (error) {
    is AiClientException -> error
    is SocketTimeoutException -> AiClientException.Timeout()
    is IOException -> AiClientException.Network()
    else -> AiClientException.Malformed(error.message ?: error.javaClass.simpleName)
}

internal fun httpError(code: Int, detail: String?): AiClientException = when (code) {
    401, 403 -> AiClientException.InvalidKey()
    429 -> AiClientException.RateLimited()
    else -> AiClientException.Http(code, detail)
}
