package com.github.blarc.ai.commits.intellij.plugin.settings.clients.claude;

import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientService
import com.github.blarc.ai.commits.intellij.plugin.settings.clients.custom.CustomCliConfiguration
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader


@Service(Service.Level.APP)
class ClaudeClientService(private val cs: CoroutineScope) :
    LLMClientService<ClaudeClientConfiguration>(cs) {

    companion object {
        @JvmStatic
        fun getInstance(): ClaudeClientService = service()
    }

    override suspend fun makeRequest(
        client: ClaudeClientConfiguration,
        text: String,
        onSuccess: suspend (r: String) -> Unit,
        onError: suspend (r: String) -> Unit
    ) {
        makeRequestWithTryCatch(function = {
            val prompt = text.replace("\"", "\\\"")

            // buildPlugin 환경에서도 동작하도록 Claude 실행 경로 결정
            val (executable, args) = findClaudeExecutable(client)

            println("[DEBUG] 실행 파일: $executable")
            println("[DEBUG] 인자: $args")

            val processBuilder = if (args.isEmpty()) {
                ProcessBuilder(executable, prompt)
            } else {
                ProcessBuilder(listOf(executable) + args + prompt)
            }

            val process = processBuilder
                .apply {
                    // 작업 디렉토리 설정
                    val workingDir = client.path?.let { File(it) }
                        ?: determineWorkingDirectory()

                    if (workingDir?.exists() == true) {
                        directory(workingDir)
                    }

                    // buildPlugin 환경을 위한 환경 변수 설정
                    environment().apply {
                        setupClaudeEnvironment(this)
                    }

                    println("[DEBUG] 작업 디렉토리: ${directory()?.absolutePath}")
                    println("[DEBUG] PATH: ${environment()["PATH"]}")
                }
                .start()

            try {
                val output = StringBuilder()

                // Trust 프롬프트 자동 처리
                val trustHandler = CoroutineScope(Dispatchers.IO).launch {
                    repeat(10) { // 10번 시도 (1초마다)
                        delay(1000)
                        try {
                            if (process.isAlive) {
                                process.outputStream.use { outputStream ->
                                    outputStream.write("1\n".toByteArray())
                                    outputStream.flush()
                                    println("[DEBUG] Trust 프롬프트 응답 전송 시도 ${it + 1}")
                                }
                            }
                        } catch (e: Exception) {
                            println("[DEBUG] Trust 응답 실패: ${e.message}")
//                            break // outputStream이 닫혔으면 더 이상 시도 안함
                        }
                    }
                }

                // 출력 읽기
                val outputJob = CoroutineScope(Dispatchers.IO).launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            println("[DEBUG] 출력: $line")

                            // Trust 프롬프트 감지
                            if (line!!.contains("Do you trust the files")) {
                                println("[DEBUG] Trust 프롬프트 감지됨")
                            }

                            // Trust 프롬프트와 관련 없는 실제 응답만 저장
                            if (!line!!.contains("Do you trust") &&
                                !line.contains("❯") &&
                                !line.contains("╭") &&
                                !line.contains("│") &&
                                !line.contains("╯") &&
                                line.isNotBlank()
                            ) {
                                output.appendLine(line)
                            }
                        }
                    }
                }

                // 더 긴 타임아웃으로 대기
                val timeoutSeconds = maxOf(client.timeout, 30) // 최소 30초
                withTimeout((timeoutSeconds * 1000).toLong()) {
                    process.awaitExit()
                    trustHandler.cancel()
                    outputJob.join()
                }

                val exitCode = process.exitValue()
                val result = output.toString().trim()

                println("[DEBUG] exitCode: $exitCode")
                println("[DEBUG] result length: ${result.length}")
                println("[DEBUG] result: $result")

                when (exitCode) {
                    0 -> {
                        if (result.isNotEmpty()) {
                            onSuccess(result)
                        } else {
                            throw RuntimeException("Claude CLI가 Trust 프롬프트에서 멈춘 것으로 보입니다. --trust-folder 옵션을 확인해보세요.")
                        }
                    }

                    127 -> {
                        val diagnostics = diagnoseClaudeSetup()
                        throw RuntimeException("Claude CLI를 찾을 수 없습니다 (exit code: 127)\n\n$diagnostics")
                    }

                    13 -> {
                        throw RuntimeException("Claude CLI 실행 권한이 없습니다 (exit code: 13)\n실행 경로: $executable")
                    }

                    else -> {
                        throw RuntimeException("Claude CLI 실패 (exit code: $exitCode)")
                    }
                }

            } catch (e: TimeoutCancellationException) {
                process.destroyForcibly()

                val debugInfo = StringBuilder()
                debugInfo.appendLine("Claude CLI 타임아웃 분석:")
                debugInfo.appendLine("- 프롬프트 길이: ${text.length}")
                debugInfo.appendLine("- 타임아웃: ${client.timeout}초")
                debugInfo.appendLine("- 실행 파일: $executable")
                debugInfo.appendLine("- 프로세스 alive: ${process.isAlive}")
                debugInfo.appendLine("- Trust 프롬프트 가능성: 높음")
                debugInfo.appendLine("해결방법:")
                debugInfo.appendLine("1. claude --help 로 --trust-folder 옵션 확인")
                debugInfo.appendLine("2. 타임아웃을 60초 이상으로 늘리기")
                debugInfo.appendLine("3. 더 짧은 프롬프트로 테스트")

                throw RuntimeException(debugInfo.toString())
            }
        }, onError = onError)
    }

    // Claude 실행 파일 찾기 (다양한 환경 지원)
    private fun findClaudeExecutable(client: ClaudeClientConfiguration): Pair<String, List<String>> {
        // 1. 클라이언트 설정에서 경로가 지정된 경우
        if (!client.path.isNullOrBlank()) {
            val result = checkClientPath(client.path!!)
            if (result != null) return result
        }

        // 2. 자동 감지 - 다양한 설치 방법 순서대로 확인
        return findClaudeInEnvironment()
    }

    // 클라이언트 설정 경로 확인
    private fun checkClientPath(path: String): Pair<String, List<String>>? {
        val claudePath = File(path, "claude")
        if (claudePath.exists() && claudePath.canExecute()) {
            return claudePath.absolutePath to emptyList()
        }

        // Node.js 스크립트 확인 (상대 경로)
        val scriptPath = File(path, "../lib/node_modules/@anthropic-ai/claude-code/cli.js")
        if (scriptPath.exists()) {
            return "node" to listOf(scriptPath.absolutePath)
        }

        return null
    }

    // 환경에서 Claude 찾기
    private fun findClaudeInEnvironment(): Pair<String, List<String>> {
        val strategies = listOf(
            ::findInPath,           // PATH에서 찾기 (가장 일반적)
            ::findInNvm,           // NVM 설치
            ::findInFnm,           // Fast Node Manager
            ::findInNodeVersionManager, // n (Node Version Manager)
            ::findInBrewNodejs,    // Homebrew Node.js
            ::findInSystemNodejs,  // 시스템 Node.js
            ::findInNpmGlobal,     // npm global 설치
            ::findInYarnGlobal,    // Yarn global 설치
            ::findInPnpmGlobal     // pnpm global 설치
        )

        strategies.forEach { strategy ->
            val result = try {
                strategy()
            } catch (e: Exception) {
                println("[DEBUG] Strategy ${strategy.javaClass.simpleName} 실패: ${e.message}")
                null
            }

            if (result != null) {
                println("[DEBUG] Claude 발견: $result")
                return result
            }
        }

        // 모두 실패하면 기본값
        return "claude" to emptyList()
    }

    // 1. PATH에서 찾기
    private fun findInPath(): Pair<String, List<String>>? {
        return if (isCommandInPath("claude")) {
            "claude" to emptyList()
        } else null
    }

    // 2. NVM에서 찾기 (동적 버전 감지)
    private fun findInNvm(): Pair<String, List<String>>? {
        val nvmDir = File("${System.getProperty("user.home")}/.nvm/versions/node")
        if (!nvmDir.exists()) return null

        // 모든 Node.js 버전 확인 (최신 버전 우선) - 수정됨
        val nodeVersions = nvmDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
            ?.sortedByDescending { parseVersionToComparable(it.name) }
            ?: return null

        nodeVersions.forEach { versionDir ->
            // claude 실행 파일 확인
            val claudeBin = File(versionDir, "bin/claude")
            if (claudeBin.exists() && claudeBin.canExecute()) {
                return claudeBin.absolutePath to emptyList()
            }

            // Node.js 스크립트 확인
            val scriptFile = File(versionDir, "lib/node_modules/@anthropic-ai/claude-code/cli.js")
            if (scriptFile.exists()) {
                val nodeExecutable = File(versionDir, "bin/node")
                val nodePath = if (nodeExecutable.exists()) nodeExecutable.absolutePath else "node"
                return nodePath to listOf(scriptFile.absolutePath)
            }
        }

        return null
    }

    private fun parseVersionToComparable(version: String): Long {
        val parts = version.removePrefix("v")
            .split(".")
            .mapNotNull { it.toIntOrNull() }

        return when (parts.size) {
            3 -> parts[0] * 1000000L + parts[1] * 1000L + parts[2]
            2 -> parts[0] * 1000000L + parts[1] * 1000L
            1 -> parts[0] * 1000000L
            else -> 0L
        }
    }

    // 3. fnm (Fast Node Manager)에서 찾기
    private fun findInFnm(): Pair<String, List<String>>? {
        val fnmDir = File("${System.getProperty("user.home")}/Library/Application Support/fnm")
        if (!fnmDir.exists()) return null

        val nodeInstalls = File(fnmDir, "node-versions")
        if (!nodeInstalls.exists()) return null

        return nodeInstalls.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { parseVersionToComparable(it.name) }  // 수정: parseVersion → parseVersionToComparable
            ?.mapNotNull { versionDir ->
                val claudeBin = File(versionDir, "installation/bin/claude")
                if (claudeBin.exists() && claudeBin.canExecute()) {
                    claudeBin.absolutePath to emptyList()
                } else {
                    val scriptFile = File(versionDir, "installation/lib/node_modules/@anthropic-ai/claude-code/cli.js")
                    if (scriptFile.exists()) {
                        "node" to listOf(scriptFile.absolutePath)
                    } else null
                }
            }?.firstOrNull()
    }

    // 4. n (Node Version Manager)에서 찾기
    private fun findInNodeVersionManager(): Pair<String, List<String>>? {
        return try {
            // n의 다양한 설치 경로 확인
            val possibleNDirs = listOf(
                "/usr/local/n/versions/node",
                "${System.getProperty("user.home")}/n/versions/node",
                "/opt/n/versions/node"
            )

            for (nDirPath in possibleNDirs) {
                val nDir = File(nDirPath)
                if (!nDir.exists()) continue

                val result = nDir.listFiles()
                    ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
                    ?.sortedByDescending { parseVersionToComparable("v${it.name}") }
                    ?.mapNotNull { versionDir ->
                        // 1. claude 바이너리 확인
                        val claudeBin = File(versionDir, "bin/claude")
                        if (claudeBin.exists() && claudeBin.canExecute()) {
                            return@mapNotNull claudeBin.absolutePath to emptyList()
                        }

                        // 2. Node.js 스크립트 확인
                        val scriptFile = File(versionDir, "lib/node_modules/@anthropic-ai/claude-code/cli.js")
                        if (scriptFile.exists()) {
                            val nodeExecutable = File(versionDir, "bin/node")
                            val nodePath = if (nodeExecutable.exists() && nodeExecutable.canExecute()) {
                                nodeExecutable.absolutePath
                            } else "node"
                            return@mapNotNull nodePath to listOf(scriptFile.absolutePath)
                        }

                        null
                    }?.firstOrNull()

                if (result != null) return result
            }

            null
        } catch (e: Exception) {
            println("[DEBUG] findInNodeVersionManager 오류: ${e.message}")
            null
        }
    }

    // 5. Homebrew Node.js에서 찾기
    private fun findInBrewNodejs(): Pair<String, List<String>>? {
        val brewPaths = listOf(
            "/opt/homebrew/bin/claude",     // Apple Silicon Mac
            "/usr/local/bin/claude",        // Intel Mac
            "/home/linuxbrew/.linuxbrew/bin/claude"  // Linux
        )

        brewPaths.forEach { path ->
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath to emptyList()
            }
        }

        return null
    }

    // 6. 시스템 Node.js에서 찾기
    private fun findInSystemNodejs(): Pair<String, List<String>>? {
        val systemPaths = listOf(
            "/usr/bin/claude",
            "/usr/local/bin/claude",
            "/opt/local/bin/claude"
        )

        systemPaths.forEach { path ->
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath to emptyList()
            }
        }

        return null
    }

    // 7. npm global에서 찾기
    private fun findInNpmGlobal(): Pair<String, List<String>>? {
        return try {
            // npm config get prefix로 global 경로 찾기
            val process = ProcessBuilder("npm", "config", "get", "prefix").start()
            val prefix = process.inputStream.bufferedReader().readText().trim()

            if (prefix.isNotEmpty()) {
                val claudePath = File(prefix, "bin/claude")
                if (claudePath.exists() && claudePath.canExecute()) {
                    return claudePath.absolutePath to emptyList()
                }

                // Node 스크립트 확인
                val scriptPath = File(prefix, "lib/node_modules/@anthropic-ai/claude-code/cli.js")
                if (scriptPath.exists()) {
                    return "node" to listOf(scriptPath.absolutePath)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // 8. Yarn global에서 찾기
    private fun findInYarnGlobal(): Pair<String, List<String>>? {
        return try {
            val process = ProcessBuilder("yarn", "global", "dir").start()
            val globalDir = process.inputStream.bufferedReader().readText().trim()

            if (globalDir.isNotEmpty()) {
                val scriptPath = File(globalDir, "node_modules/@anthropic-ai/claude-code/cli.js")
                if (scriptPath.exists()) {
                    return "node" to listOf(scriptPath.absolutePath)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // 9. pnpm global에서 찾기
    private fun findInPnpmGlobal(): Pair<String, List<String>>? {
        return try {
            val process = ProcessBuilder("pnpm", "root", "-g").start()
            val globalRoot = process.inputStream.bufferedReader().readText().trim()

            if (globalRoot.isNotEmpty()) {
                val scriptPath = File(globalRoot, "@anthropic-ai/claude-code/cli.js")
                if (scriptPath.exists()) {
                    return "node" to listOf(scriptPath.absolutePath)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // 버전 파싱 유틸리티
    private fun parseVersion(version: String): List<Int> {
        return version.removePrefix("v")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    // PATH에서 명령어 확인
    private fun isCommandInPath(command: String): Boolean {
        return try {
            val process = if (System.getProperty("os.name").lowercase().contains("win")) {
                ProcessBuilder("where", command)
            } else {
                ProcessBuilder("which", command)
            }
            process.start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    // 작업 디렉토리 결정
    private fun determineWorkingDirectory(): File? {
        return try {
            // 현재 프로젝트 디렉토리 시도 (IntelliJ 환경)
            val projectManager = com.intellij.openapi.project.ProjectManager.getInstance()
            val project = projectManager.openProjects.firstOrNull()
            project?.basePath?.let { File(it) }
        } catch (e: Exception) {
            // IntelliJ API 사용 불가능한 경우 사용자 홈 디렉토리
            File(System.getProperty("user.home"))
        }
    }

    // buildPlugin 환경을 위한 환경 변수 설정 (동적 경로 지원)
    private fun setupClaudeEnvironment(env: MutableMap<String, String>) {
        // 1. PATH 확장 - 동적 Node.js 버전 지원
        val currentPath = env["PATH"] ?: System.getenv("PATH") ?: ""
        val additionalPaths = mutableListOf<String>()

        // NVM 경로들 (모든 버전)
        val nvmDir = File("${System.getProperty("user.home")}/.nvm/versions/node")
        if (nvmDir.exists()) {
            nvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
                ?.sortedByDescending { parseVersionToComparable(it.name) }  // 수정: parseVersion → parseVersionToComparable
                ?.take(3) // 최신 3개 버전만
                ?.forEach { versionDir ->
                    val binDir = File(versionDir, "bin")
                    if (binDir.exists()) {
                        additionalPaths.add(binDir.absolutePath)
                    }
                }
        }

        // 기타 일반적인 경로들
        additionalPaths.addAll(listOf(
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/home/linuxbrew/.linuxbrew/bin"
        ).filter { File(it).exists() })

        if (additionalPaths.isNotEmpty()) {
            env["PATH"] = "${additionalPaths.joinToString(":")}:$currentPath"
        }

        // 2. NODE_PATH 설정 (최신 Node.js 버전 기준)
        val latestNodeModules = nvmDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
            ?.maxByOrNull { parseVersionToComparable(it.name) }  // 수정: parseVersion → parseVersionToComparable
            ?.let { File(it, "lib/node_modules") }

        if (latestNodeModules?.exists() == true) {
            env["NODE_PATH"] = latestNodeModules.absolutePath
        }

        // 3. HOME 디렉토리 명시적 설정
        env["HOME"] = System.getProperty("user.home")
    }

    // 진단 정보 생성 (강화된 버전)
    private fun diagnoseClaudeSetup(): String {
        val diag = StringBuilder()

        diag.appendLine("=== Claude CLI 환경 진단 ===")
        diag.appendLine("OS: ${System.getProperty("os.name")}")
        diag.appendLine("Architecture: ${System.getProperty("os.arch")}")
        diag.appendLine("User Home: ${System.getProperty("user.home")}")
        diag.appendLine("Working Directory: ${System.getProperty("user.dir")}")
        diag.appendLine("Java Version: ${System.getProperty("java.version")}")

        // 환경 변수 확인
        diag.appendLine("\n--- 환경 변수 ---")
        diag.appendLine("PATH: ${System.getenv("PATH")}")
        diag.appendLine("HOME: ${System.getenv("HOME")}")
        diag.appendLine("NODE_PATH: ${System.getenv("NODE_PATH") ?: "설정되지 않음"}")

        // Node.js 환경 확인
        diag.appendLine("\n--- Node.js 환경 ---")
        checkCommand("node --version")?.let { diag.appendLine("Node.js: $it") }
        checkCommand("npm --version")?.let { diag.appendLine("npm: $it") }
        checkCommand("yarn --version")?.let { diag.appendLine("Yarn: $it") }
        checkCommand("pnpm --version")?.let { diag.appendLine("pnpm: $it") }

        // NVM 설치된 Node.js 버전들
        val nvmDir = File("${System.getProperty("user.home")}/.nvm/versions/node")
        if (nvmDir.exists()) {
            diag.appendLine("\n--- NVM 설치된 Node.js 버전들 ---")
            nvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
                ?.sortedByDescending { parseVersionToComparable(it.name) }  // 수정: parseVersion → parseVersionToComparable
                ?.forEach { versionDir ->
                    val claudeBin = File(versionDir, "bin/claude")
                    val scriptFile = File(versionDir, "lib/node_modules/@anthropic-ai/claude-code/cli.js")
                    diag.appendLine("${versionDir.name}: claude=${claudeBin.exists()}, script=${scriptFile.exists()}")
                }
        }

        // 각 전략별 확인
        diag.appendLine("\n--- Claude 찾기 결과 ---")
        val strategies = mapOf(
            "PATH" to ::findInPath,
            "NVM" to ::findInNvm,
            "fnm" to ::findInFnm,
            "n" to ::findInNodeVersionManager,
            "Homebrew" to ::findInBrewNodejs,
            "System" to ::findInSystemNodejs,
            "npm global" to ::findInNpmGlobal,
            "Yarn global" to ::findInYarnGlobal,
            "pnpm global" to ::findInPnpmGlobal
        )

        strategies.forEach { (name, strategy) ->
            val result = try {
                strategy()
            } catch (e: Exception) {
                null
            }

            if (result != null) {
                diag.appendLine("✅ $name: ${result.first} ${result.second.joinToString(" ")}")
            } else {
                diag.appendLine("❌ $name: 찾지 못함")
            }
        }

        // which/where 명령어 결과
        try {
            val whichCommand = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val whichResult = ProcessBuilder(whichCommand, "claude")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
            diag.appendLine("\n$whichCommand claude: $whichResult")
        } catch (e: Exception) {
            diag.appendLine("\n명령어 찾기 실패: ${e.message}")
        }

        return diag.toString()
    }

    // 명령어 실행 결과 확인
    private fun checkCommand(command: String): String? {
        return try {
            val parts = command.split(" ")
            val process = ProcessBuilder(parts).start()
            if (process.waitFor() == 0) {
                process.inputStream.bufferedReader().readText().trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }
}