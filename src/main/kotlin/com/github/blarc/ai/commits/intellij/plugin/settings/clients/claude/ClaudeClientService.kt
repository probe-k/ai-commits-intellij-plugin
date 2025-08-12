package com.github.blarc.ai.commits.intellij.plugin.settings.clients.claude;

import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader


@Service(Service.Level.APP)
class ClaudeClientService(private val cs: CoroutineScope) : LLMClientService<ClaudeClientConfiguration>(cs) {

    companion object {
        @JvmStatic
        fun getInstance(): ClaudeClientService = service()
    }

    override suspend fun makeRequest(client: ClaudeClientConfiguration, text: String, onSuccess: suspend (r: String) -> Unit, onError: suspend (r: String) -> Unit) {
        makeRequestWithTryCatch(function = {
            // CLI 기반 호출 로직
            val prompt = text
            // path가 설정되어 있으면 전체 경로로 명령어 구성
            val command = if (!client.path.isNullOrBlank()) {
                File(client.path, client.command).absolutePath
            } else {
                client.command
            }

            val process = ProcessBuilder(command, prompt)
                .redirectErrorStream(true)
                .start()

            try {
                val output = StringBuilder()

                val job = CoroutineScope(Dispatchers.IO).launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            output.appendLine(line)
                        }
                    }
                }

                withTimeout((client.timeout * 1000).toLong()) { // 70초 제한
                    process.awaitExit()
                    job.join()
                }

                if (process.exitValue() != 0) {
                    throw RuntimeException("Claude CLI 실패 (exit code: ${process.exitValue()}): $output")
                }

                onSuccess(output.toString().trim())
            } catch (e: TimeoutCancellationException) {
                process.destroyForcibly() // 프로세스 강제 종료
                throw RuntimeException("Claude CLI 호출이 제한 시간(${client.timeout}초) 안에 끝나지 않아 강제 종료되었습니다.")
            }
        }, onError = onError)
    }
}
