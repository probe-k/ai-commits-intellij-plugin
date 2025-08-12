package com.github.blarc.ai.commits.intellij.plugin.settings.clients.custom;

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
class CustomCliService(private val cs: CoroutineScope) : LLMClientService<CustomCliConfiguration>(cs) {

    companion object {
        @JvmStatic
        fun getInstance(): CustomCliService = service()
    }

    override suspend fun makeRequest(
        client: CustomCliConfiguration,
        text: String,
        onSuccess: suspend (r: String) -> Unit,
        onError: suspend (r: String) -> Unit
    ) {
        makeRequestWithTryCatch(function = {
            // 명령어 구성 - 실제 위치 우선 확인
            val command = resolveActualCommand(client)

            println("[DEBUG] 최종 실행 명령어: $command")
            println("[DEBUG] 프롬프트 길이: ${text.length}")
            
            // 명령어 존재 여부 미리 확인
            if (!verifyCommandExists(command)) {
                val debugInfo = buildString {
                    appendLine("명령어를 찾을 수 없습니다: $command")
                    appendLine("\n=== 디버그 정보 ===")
                    appendLine("원래 명령어: ${client.command}")
                    appendLine("설정된 경로: ${client.path ?: "없음"}")
                    appendLine("현재 PATH: ${System.getenv("PATH")}")
                    
                    if (client.command.contains("claude")) {
                        appendLine("\n=== Claude 위치 검색 결과 ===")
                        val actualClaude = findActualClaudeLocation()
                        if (actualClaude != null) {
                            appendLine("✅ 발견된 Claude: $actualClaude")
                            appendLine("해결방법: 설정에서 path를 '$actualClaude'로 변경하거나")
                            appendLine("           command를 '$actualClaude'로 변경하세요.")
                        } else {
                            appendLine("❌ Claude를 찾을 수 없음")
                            appendLine("해결방법: npm install -g @anthropic-ai/claude-code 실행")
                        }
                    }
                }
                throw RuntimeException(debugInfo)
            }
            
            // 명령어 분석 및 환경 디버깅
            analyzeAndDebugCommand(command)

            val process = ProcessBuilder(command, text)
                .apply {
                    // 작업 디렉토리 설정
                    if (!client.path.isNullOrBlank()) {
                        val workingDir = File(client.path!!)
                        if (workingDir.exists() && workingDir.isDirectory) {
                            directory(workingDir)
                            println("[DEBUG] 작업 디렉토리: ${workingDir.absolutePath}")
                        }
                    }
                    
                    // 스마트 환경 설정 (명령어 기반 자동 PATH 설정)
                    setupSmartEnvironment(environment(), command)
                }
                .start()

            // 변수들을 try 블록 밖에서 선언 (람다에서 수정 가능하도록)
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            val lastOutputTime = longArrayOf(System.currentTimeMillis()) // 배열로 래핑해서 수정 가능하게
            val lineCount = intArrayOf(0) // 배열로 래핑

            try {
                println("[DEBUG] 프로세스 시작됨, PID 체크 중...")
                println("[DEBUG] 프로세스 alive: ${process.isAlive}")

                // 실시간 출력 읽기 (논블로킹)
                val outputJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                            var line: String?
                            while (br.readLine().also { line = it } != null) {
                                lineCount[0]++
                                lastOutputTime[0] = System.currentTimeMillis()
                                println("[REALTIME] 출력 #${lineCount[0]}: $line")
                                output.appendLine(line)
                            }
                        }
                        println("[DEBUG] 표준 출력 스트림 완료")
                    } catch (e: Exception) {
                        println("[DEBUG] 출력 읽기 오류: ${e.message}")
                    }
                }

                // 실시간 에러 읽기 (논블로킹)
                val errorJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { br ->
                            var line: String?
                            while (br.readLine().also { line = it } != null) {
                                lastOutputTime[0] = System.currentTimeMillis()
                                println("[REALTIME] 에러: $line")
                                errorOutput.appendLine(line)
                            }
                        }
                        println("[DEBUG] 표준 에러 스트림 완료")
                    } catch (e: Exception) {
                        println("[DEBUG] 에러 읽기 오류: ${e.message}")
                    }
                }

                // 프로세스 모니터링 및 hang 감지
                val monitorJob = CoroutineScope(Dispatchers.IO).launch {
                    var checkCount = 0
                    var hangWarningCount = 0
                    
                    while (process.isAlive) {
                        checkCount++
                        val timeSinceLastOutput = System.currentTimeMillis() - lastOutputTime[0]
                        
                        println("[DEBUG] 모니터링 #$checkCount - alive: ${process.isAlive}, " +
                                "마지막 출력: ${timeSinceLastOutput}ms 전, 총 라인: ${lineCount[0]}")
                        
                        when {
                            timeSinceLastOutput > 60000 && lineCount[0] == 0 -> {
                                // 60초간 출력이 전혀 없으면 강제 종료
                                println("[ERROR] 60초간 출력이 없어 프로세스를 강제 종료합니다.")
                                process.destroyForcibly()
                                break
                            }
                            timeSinceLastOutput > 30000 && lineCount[0] == 0 -> {
                                hangWarningCount++
                                println("[WARNING] $hangWarningCount - 30초간 출력이 없습니다.")
                                println("[WARNING] 명령어가 입력을 기다리거나 hang 상태일 수 있습니다.")
                                
                                // Trust 프롬프트나 다른 입력 대기 상황 체크
                                if (hangWarningCount == 1) {
                                    println("[INFO] Claude CLI의 Trust 프롬프트일 가능성이 있습니다.")
                                    println("[INFO] 자동 응답을 시도합니다...")
                                    
                                    try {
                                        process.outputStream?.use { out ->
                                            out.write("1\n".toByteArray())
                                            out.flush()
                                            println("[INFO] '1' 응답 전송됨")
                                        }
                                    } catch (e: Exception) {
                                        println("[WARNING] 자동 응답 실패: ${e.message}")
                                    }
                                }
                            }
                            timeSinceLastOutput > 10000 && checkCount > 5 -> {
                                println("[INFO] ${timeSinceLastOutput/1000}초간 새로운 출력이 없습니다.")
                            }
                        }
                        
                        kotlinx.coroutines.delay(2000) // 2초마다 체크
                    }
                    println("[DEBUG] 프로세스 모니터링 완료")
                }

                // 타임아웃으로 대기 (더 짧은 간격으로 체크)
                val timeoutSeconds = maxOf(client.timeout, 10)
                println("[DEBUG] 타임아웃: ${timeoutSeconds}초 대기 시작")
                
                withTimeout((timeoutSeconds * 1000).toLong()) {
                    process.awaitExit()
                    println("[DEBUG] 프로세스 정상 종료됨")
                    
                    // 모든 작업 완료까지 대기
                    outputJob.join()
                    errorJob.join()
                    monitorJob.cancel()
                }

                val exitCode = process.exitValue()
                val result = output.toString().trim()
                val error = errorOutput.toString().trim()

                println("[DEBUG] === 최종 결과 ===")
                println("[DEBUG] exitCode: $exitCode")
                println("[DEBUG] 총 출력 라인: ${lineCount[0]}")
                println("[DEBUG] result length: ${result.length}")
                println("[DEBUG] error length: ${error.length}")
                
                if (result.isNotEmpty()) {
                    println("[DEBUG] 결과 미리보기: ${result.take(200)}...")
                }
                if (error.isNotEmpty()) {
                    println("[DEBUG] 에러 미리보기: ${error.take(200)}...")
                }

                when (exitCode) {
                    0 -> {
                        if (result.isNotEmpty()) {
                            onSuccess(result)
                        } else if (error.isNotEmpty()) {
                            // 출력이 없고 에러만 있는 경우 에러를 결과로 사용
                            onSuccess(error)
                        } else {
                            throw RuntimeException("명령어 실행은 성공했지만 출력이 없습니다.")
                        }
                    }
                    else -> {
                        val errorMessage = if (error.isNotEmpty()) {
                            error
                        } else if (result.isNotEmpty()) {
                            result
                        } else {
                            "알 수 없는 오류가 발생했습니다."
                        }
                        throw RuntimeException("명령어 실행 실패 (exit code: $exitCode): $errorMessage")
                    }
                }

            } catch (e: TimeoutCancellationException) {
                println("[ERROR] 타임아웃 발생! 프로세스 강제 종료 중...")
                process.destroyForcibly()
                
                val currentOutput = output.toString().trim()
                val currentError = errorOutput.toString().trim()
                
                val timeoutInfo = buildString {
                    appendLine("=== CLI 명령어 타임아웃 (${client.timeout}초) ===")
                    appendLine("명령어: $command")
                    appendLine("프롬프트 길이: ${text.length}자")
                    appendLine("총 출력 라인 수: ${lineCount[0]}")
                    appendLine("마지막 출력 시간: ${System.currentTimeMillis() - lastOutputTime[0]}ms 전")
                    
                    if (currentOutput.isNotEmpty()) {
                        appendLine("\n--- 타임아웃 전까지 받은 출력 ---")
                        appendLine(currentOutput.take(500) + if (currentOutput.length > 500) "..." else "")
                    }
                    
                    if (currentError.isNotEmpty()) {
                        appendLine("\n--- 타임아웃 전까지 받은 에러 ---")
                        appendLine(currentError.take(500) + if (currentError.length > 500) "..." else "")
                    }
                    
                    appendLine("\n--- 해결방법 ---")
                    when {
                        lineCount[0] == 0 -> {
                            appendLine("• 출력이 전혀 없었습니다. 명령어나 경로를 확인하세요.")
                            appendLine("• PATH 설정이나 권한 문제일 수 있습니다.")
                        }
                        currentOutput.contains("Do you trust") || currentOutput.contains("Trust") -> {
                            appendLine("• Trust 프롬프트가 감지되었습니다.")
                            appendLine("• claude --help로 --trust-folder 옵션을 확인하세요.")
                        }
                        else -> {
                            appendLine("• 타임아웃을 ${client.timeout * 2}초 이상으로 늘려보세요.")
                            appendLine("• 더 짧은 프롬프트로 테스트해보세요.")
                        }
                    }
                }
                
                throw RuntimeException(timeoutInfo)
            }
        }, onError = onError)
    }

    // 실제 명령어 위치 해결
    private fun resolveActualCommand(client: CustomCliConfiguration): String {
        // 1. 클라이언트에서 path가 지정된 경우 우선 사용
        if (!client.path.isNullOrBlank()) {
            val specifiedCommand = File(client.path, client.command).absolutePath
            if (File(specifiedCommand).exists()) {
                return specifiedCommand
            }
        }

        // 2. which 명령어로 실제 위치 찾기
        val actualLocation = findCommandWithWhich(client.command)
        if (actualLocation != null) {
            println("[DEBUG] which로 찾은 위치: $actualLocation")
            return actualLocation
        }

        // 3. Claude인 경우 특별 처리
        if (client.command.contains("claude")) {
            val claudeLocation = findActualClaudeLocation()
            if (claudeLocation != null) {
                println("[DEBUG] Claude 특별 검색으로 찾은 위치: $claudeLocation")
                return claudeLocation
            }
        }

        // 4. 원래 명령어 그대로 반환 (PATH에서 찾기를 기대)
        return client.command
    }

    // which 명령어로 실제 위치 찾기
    private fun findCommandWithWhich(command: String): String? {
        return try {
            val process = ProcessBuilder(getWhichCommand(), command).start()
            val location = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && location.isNotEmpty() && File(location).exists()) {
                location
            } else null
        } catch (e: Exception) {
            println("[DEBUG] which 명령어 실패: ${e.message}")
            null
        }
    }

    // Claude 실제 위치 찾기 (다양한 위치 검색)
    private fun findActualClaudeLocation(): String? {
        val userHome = System.getProperty("user.home")
        
        // 가능한 Claude 설치 위치들
        val possibleLocations = mutableListOf(
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "$userHome/.npm-packages/bin/claude",
            "$userHome/.npm/bin/claude",
            "$userHome/node_modules/.bin/claude"
        )

        // npm global bin 디렉토리 확인
        val npmGlobalBin = getNpmGlobalBin()
        if (npmGlobalBin != null) {
            possibleLocations.add("$npmGlobalBin/claude")
        }

        // NVM 환경에서 npm global 위치 확인
        val nvmDir = File("$userHome/.nvm/versions/node")
        if (nvmDir.exists()) {
            nvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
                ?.sortedByDescending { parseVersionToComparable(it.name) }
                ?.take(3) // 최신 3개 버전만 확인
                ?.forEach { versionDir ->
                    possibleLocations.add("${versionDir.absolutePath}/lib/node_modules/.bin/claude")
                    possibleLocations.add("${versionDir.absolutePath}/bin/claude")
                }
        }

        println("[DEBUG] Claude 검색 위치들:")
        possibleLocations.forEach { location ->
            println("[DEBUG] 확인 중: $location")
        }

        // 각 위치 확인
        possibleLocations.forEach { location ->
            val file = File(location)
            if (file.exists() && file.canExecute()) {
                println("[DEBUG] ✅ Claude 발견: $location")
                return location
            } else {
                println("[DEBUG] ❌ 없음: $location (exists=${file.exists()}, canExecute=${file.canExecute()})")
            }
        }

        println("[DEBUG] Claude를 찾을 수 없었습니다.")
        return null
    }

    // npm global bin 디렉토리 얻기
    private fun getNpmGlobalBin(): String? {
        return try {
            val process = ProcessBuilder("npm", "config", "get", "prefix").start()
            val prefix = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && prefix.isNotEmpty()) {
                "$prefix/bin"
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // 명령어 존재 여부 확인
    private fun verifyCommandExists(command: String): Boolean {
        val file = File(command)
        return if (file.isAbsolute) {
            file.exists() && file.canExecute()
        } else {
            // PATH에서 찾을 수 있는지 확인
            findCommandWithWhich(command) != null
        }
    }
    private fun analyzeAndDebugCommand(command: String) {
        println("[DEBUG] === 명령어 분석 및 환경 확인 ===")
        println("[DEBUG] OS: ${System.getProperty("os.name")}")
        println("[DEBUG] Architecture: ${System.getProperty("os.arch")}")
        println("[DEBUG] 현재 PATH: ${System.getenv("PATH")}")
        println("[DEBUG] HOME: ${System.getProperty("user.home")}")
        
        // 명령어 타입 분석
        val commandType = detectCommandType(command)
        println("[DEBUG] 감지된 명령어 타입: $commandType")
        
        // 해당 타입의 실행환경 확인
        checkRuntimeEnvironment(commandType)
    }

    // 명령어 타입 감지
    private fun detectCommandType(command: String): CommandType {
        val cmd = command.lowercase()
        return when {
            cmd.contains("claude") -> CommandType.NODE_BASED
            cmd.contains("node") || cmd.endsWith(".js") -> CommandType.NODE_BASED
            cmd.contains("python") || cmd.endsWith(".py") -> CommandType.PYTHON_BASED
            cmd.contains("java") || cmd.endsWith(".jar") -> CommandType.JAVA_BASED
            cmd.contains("npm") || cmd.contains("npx") -> CommandType.NODE_BASED
            cmd.contains("openai") -> CommandType.NODE_BASED  // OpenAI CLI는 보통 npm으로 설치
            else -> CommandType.SYSTEM_BINARY
        }
    }

    // 실행환경 확인
    private fun checkRuntimeEnvironment(type: CommandType) {
        when (type) {
            CommandType.NODE_BASED -> checkNodeEnvironment()
            CommandType.PYTHON_BASED -> checkPythonEnvironment()
            CommandType.JAVA_BASED -> checkJavaEnvironment()
            CommandType.SYSTEM_BINARY -> println("[DEBUG] 시스템 바이너리로 감지됨")
        }
    }

    // Node.js 환경 확인
    private fun checkNodeEnvironment() {
        println("[DEBUG] --- Node.js 환경 확인 ---")
        checkCommandExists("node", "--version")
        checkCommandExists("npm", "--version")
        
        // NVM 디렉토리 확인
        val nvmDir = File("${System.getProperty("user.home")}/.nvm/versions/node")
        if (nvmDir.exists()) {
            println("[DEBUG] NVM 설치됨: ${nvmDir.absolutePath}")
            nvmDir.listFiles()?.take(3)?.forEach { version ->
                val nodeExec = File(version, "bin/node")
                println("[DEBUG] NVM ${version.name}: node 존재=${nodeExec.exists()}")
            }
        } else {
            println("[DEBUG] NVM 설치되지 않음")
        }
    }

    // Python 환경 확인  
    private fun checkPythonEnvironment() {
        println("[DEBUG] --- Python 환경 확인 ---")
        checkCommandExists("python", "--version")
        checkCommandExists("python3", "--version")
    }

    // Java 환경 확인
    private fun checkJavaEnvironment() {
        println("[DEBUG] --- Java 환경 확인 ---")
        checkCommandExists("java", "--version")
    }

    // 명령어 존재 여부 확인
    private fun checkCommandExists(command: String, versionFlag: String) {
        try {
            val whichProcess = ProcessBuilder(getWhichCommand(), command).start()
            val path = whichProcess.inputStream.bufferedReader().readText().trim()
            val exitCode = whichProcess.waitFor()
            
            if (exitCode == 0 && path.isNotEmpty()) {
                println("[DEBUG] $command 찾음: $path")
                
                // 버전 확인
                try {
                    val versionProcess = ProcessBuilder(command, versionFlag).start()
                    val version = versionProcess.inputStream.bufferedReader().readText().trim()
                    val versionExit = versionProcess.waitFor()
                    if (versionExit == 0) {
                        println("[DEBUG] $command 버전: $version")
                    }
                } catch (e: Exception) {
                    println("[DEBUG] $command 버전 확인 실패: ${e.message}")
                }
            } else {
                println("[DEBUG] $command 찾지 못함 (exit code: $exitCode)")
            }
        } catch (e: Exception) {
            println("[DEBUG] $command 확인 실패: ${e.message}")
        }
    }

    // OS별 which 명령어
    private fun getWhichCommand(): String {
        return if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
    }

    // 스마트 환경 설정 (명령어 기반 자동 PATH 설정)
    private fun setupSmartEnvironment(env: MutableMap<String, String>, command: String) {
        val currentPath = env["PATH"] ?: System.getenv("PATH") ?: ""
        val commandType = detectCommandType(command)
        
        println("[DEBUG] --- 스마트 환경 설정 시작 ---")
        println("[DEBUG] 명령어 타입: $commandType")
        
        val additionalPaths = mutableSetOf<String>()
        
        // 1. 기본 시스템 경로들 (모든 명령어 타입에 공통)
        additionalPaths.addAll(getCommonSystemPaths())
        
        // 2. 명령어 타입별 특화 경로들
        when (commandType) {
            CommandType.NODE_BASED -> {
                additionalPaths.addAll(getNodePaths())
            }
            CommandType.PYTHON_BASED -> {
                additionalPaths.addAll(getPythonPaths())
            }
            CommandType.JAVA_BASED -> {
                additionalPaths.addAll(getJavaPaths())
            }
            CommandType.SYSTEM_BINARY -> {
                // 시스템 바이너리는 기본 경로들만 사용
            }
        }

        // 3. buildPlugin 환경 감지 및 추가 경로
        if (isBuildPluginEnvironment()) {
            println("[DEBUG] buildPlugin 환경 감지됨 - 확장된 PATH 설정")
            additionalPaths.addAll(getBuildPluginPaths())
        }

        // 4. 존재하는 경로만 필터링
        val validPaths = additionalPaths.filter { path ->
            File(path).exists().also { exists ->
                if (!exists) {
                    println("[DEBUG] 경로 제외 (존재하지 않음): $path")
                }
            }
        }

        // 5. PATH 업데이트
        if (validPaths.isNotEmpty()) {
            val newPath = "${validPaths.joinToString(":")}:$currentPath"
            env["PATH"] = newPath
            println("[DEBUG] PATH 업데이트됨:")
            println("[DEBUG] 추가된 경로들: ${validPaths.joinToString(":")}")
        }

        // 6. 기타 환경 변수 설정
        env["HOME"] = System.getProperty("user.home")
        env["USER"] = System.getProperty("user.name")
        
        println("[DEBUG] 최종 PATH: ${env["PATH"]}")
    }

    // 공통 시스템 경로들
    private fun getCommonSystemPaths(): List<String> {
        return listOf(
            "/usr/local/bin",
            "/opt/homebrew/bin",        // Apple Silicon Mac
            "/usr/bin",
            "/bin",
            "/opt/local/bin",           // MacPorts
            "/home/linuxbrew/.linuxbrew/bin"  // Linux Homebrew
        )
    }

    // Node.js 관련 경로들
    private fun getNodePaths(): List<String> {
        val paths = mutableListOf<String>()
        val userHome = System.getProperty("user.home")
        
        // NVM 경로들
        val nvmDir = File("$userHome/.nvm/versions/node")
        if (nvmDir.exists()) {
            nvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
                ?.sortedByDescending { parseVersionToComparable(it.name) }
                ?.take(5) // 최신 5개 버전
                ?.forEach { versionDir ->
                    val binDir = File(versionDir, "bin")
                    if (binDir.exists()) {
                        paths.add(binDir.absolutePath)
                    }
                }
        }
        
        // 기타 Node.js 경로들
        paths.addAll(listOf(
            "/usr/local/nodejs/bin",
            "$userHome/.npm-global/bin",
            "/opt/node/bin"
        ))
        
        return paths
    }

    // Python 관련 경로들
    private fun getPythonPaths(): List<String> {
        val userHome = System.getProperty("user.home")
        return listOf(
            "/usr/local/python/bin",
            "/opt/python/bin", 
            "$userHome/.pyenv/shims",
            "$userHome/.local/bin",
            "/Library/Frameworks/Python.framework/Versions/Current/bin"
        )
    }

    // Java 관련 경로들
    private fun getJavaPaths(): List<String> {
        val userHome = System.getProperty("user.home")
        return listOf(
            "/usr/local/java/bin",
            "/opt/java/bin",
            "$userHome/.sdkman/candidates/java/current/bin",
            "/Library/Java/JavaVirtualMachines/*/Contents/Home/bin"
        )
    }

    // buildPlugin 환경용 추가 경로들
    private fun getBuildPluginPaths(): List<String> {
        val userHome = System.getProperty("user.home")
        return listOf(
            "$userHome/bin",
            "/snap/bin",                // Snap packages
            "/usr/games",
            "/usr/local/games",
            "$userHome/.cargo/bin",     // Rust
            "$userHome/go/bin",         // Go
            "$userHome/.deno/bin"       // Deno
        )
    }

    // buildPlugin 환경 감지
    private fun isBuildPluginEnvironment(): Boolean {
        val javaCommand = System.getProperty("sun.java.command") ?: ""
        val isGradleBuild = javaCommand.contains("gradle") || javaCommand.contains("build")
        val hasLimitedPath = (System.getenv("PATH")?.split(":")?.size ?: 0) < 5
        
        return isGradleBuild || hasLimitedPath
    }

    // 버전 파싱 유틸리티
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

    // 명령어 타입 열거형
    private enum class CommandType {
        NODE_BASED,      // Node.js 기반 (claude, openai, npm 등)
        PYTHON_BASED,    // Python 기반
        JAVA_BASED,      // Java 기반
        SYSTEM_BINARY    // 시스템 바이너리
    }
}
