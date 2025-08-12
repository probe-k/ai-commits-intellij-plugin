package com.github.blarc.ai.commits.intellij.plugin.settings.clients

import com.github.blarc.ai.commits.intellij.plugin.*
import com.github.blarc.ai.commits.intellij.plugin.AICommitsBundle.message
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import kotlin.reflect.KMutableProperty0

abstract class LLMClientPanel(
    private val clientConfiguration: LLMClientConfiguration,
) {

    val commandTextField = JBTextField()
    val pathTextField = JBTextField()
    val socketTimeoutTextField = JBTextField()
    val verifyLabel = JBLabel()

    open fun create() = panel {
        nameRow()
        verifyRow()
    }

    open fun Panel.nameRow() {
        row {
            label(message("settings.llmClient.name"))
                .widthGroup("label")
            textField()
                .bindText(clientConfiguration::name)
                .align(Align.FILL)
                .validationOnInput { notBlank(it.text) }
        }
    }

    open fun Panel.pathRow(property: KMutableProperty0<String?>) {
        row {
            label(message("settings.llmClient.path")).widthGroup("label")
            cell(pathTextField)
                .bindText({ property.get() ?: "" }, property::set)
                .align(Align.FILL)
                .resizableColumn()
        }
    }

    open fun Panel.commandRow(property: KMutableProperty0<String>) {
        row {
            label(message("settings.llmClient.command")).widthGroup("label")
            cell(commandTextField)
                .bindText(property)
                .resizableColumn()
                .align(Align.FILL)
                .validationOnInput { notBlank(it.text) }
        }
    }

    // 문제 1 해결: validation 로직 수정
    open fun Panel.timeoutRow(property: KMutableProperty0<Int>) {
        row {
            label(message("settings.llmClient.timeout")).widthGroup("label")
            cell(socketTimeoutTextField)
                .bindIntText(
                    getter = { property.get() },
                    setter = { value ->
                        // 문제 2 해결: 안전한 값 설정
                        val safeValue = maxOf(value, 30)
                        property.set(safeValue)
                    }
                )
                .resizableColumn()
                .align(Align.FILL)
                .validationOnInput { textField ->
                    val text = textField.text.trim()
                    when {
                        text.isEmpty() -> null // 빈 값은 허용하고 Apply 시점에서 검증
                        text.toIntOrNull() == null -> ValidationInfo("숫자를 입력해주세요", textField)
                        // 문제 3 해결: 안전한 Int 변환
                        text.toIntOrNull()?.let { it < 30 } == true ->
                            ValidationInfo("최소 30초 이상 설정해주세요 (자동으로 30초로 조정됩니다)", textField)
                        text.toIntOrNull()?.let { it > 3600 } == true ->
                            ValidationInfo("최대 3600초까지 설정 가능합니다", textField)
                        else -> null
                    }
                }
                // 문제 4 해결: Apply 시점 validation 추가
                .validationOnApply { textField ->
                    val text = textField.text.trim()
                    when {
                        text.isEmpty() -> ValidationInfo("타임아웃 값을 입력해주세요", textField)
                        text.toIntOrNull() == null -> ValidationInfo("유효한 숫자를 입력해주세요", textField)
                        else -> null
                    }
                }
            contextHelp(message("settings.llmClient.timeout.contextHelp"))
                .align(AlignX.LEFT)
        }
    }

    // 문제 5 해결: 더 안전한 timeoutRow 대안
    open fun Panel.timeoutRowAlternative(property: KMutableProperty0<Int>) {
        row {
            label(message("settings.llmClient.timeout")).widthGroup("label")
            intTextField(range = 30..3600) // 범위 제한으로 자동 validation
                .bindIntText(
                    getter = { property.get() },
                    setter = { value -> property.set(maxOf(value, 30)) }
                )
                .resizableColumn()
                .align(Align.FILL)
            contextHelp(message("settings.llmClient.timeout.contextHelp"))
                .align(AlignX.LEFT)
        }
    }

    // 문제 6 해결: 수동 바인딩 방식 (가장 안전)
    open fun Panel.timeoutRowManual(property: KMutableProperty0<Int>) {
        row {
            label(message("settings.llmClient.timeout")).widthGroup("label")
            cell(socketTimeoutTextField)
                .applyToComponent {
                    // 초기값 설정
                    text = property.get().toString()

                    // 실시간 validation
                    addActionListener {
                        val value = text.toIntOrNull()
                        if (value != null && value >= 30) {
                            property.set(value)
                        }
                    }
                }
                .resizableColumn()
                .align(Align.FILL)
                .validationOnApply { textField ->
                    val text = textField.text.trim()
                    val value = text.toIntOrNull()
                    when {
                        text.isEmpty() -> ValidationInfo("타임아웃 값을 입력해주세요", textField)
                        value == null -> ValidationInfo("유효한 숫자를 입력해주세요", textField)
                        value < 30 -> ValidationInfo("최소 30초 이상이어야 합니다", textField)
                        value > 3600 -> ValidationInfo("최대 3600초까지 설정 가능합니다", textField)
                        else -> {
                            // Apply 시점에서 실제 값 적용
                            property.set(value)
                            null
                        }
                    }
                }
            contextHelp(message("settings.llmClient.timeout.contextHelp"))
                .align(AlignX.LEFT)
        }
    }

    open fun Panel.verifyRow() {
        row {
            cell(verifyLabel)
                .applyToComponent {
                    setAllowAutoWrapping(true)
                    setCopyable(true)
                }
                .align(AlignX.LEFT)

            button(message("settings.verifyToken")) { verifyConfiguration() }
                .align(AlignX.RIGHT)
                .align(AlignY.TOP)
                .widthGroup("button")
        }
    }

    abstract fun verifyConfiguration()
}