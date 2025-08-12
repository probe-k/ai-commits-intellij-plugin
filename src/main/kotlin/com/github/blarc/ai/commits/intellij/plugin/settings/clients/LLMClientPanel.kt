package com.github.blarc.ai.commits.intellij.plugin.settings.clients

import com.github.blarc.ai.commits.intellij.plugin.*
import com.github.blarc.ai.commits.intellij.plugin.AICommitsBundle.message
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

    open fun Panel.timeoutRow(property: KMutableProperty0<Int>) {
        row {
            label(message("settings.llmClient.timeout")).widthGroup("label")
            cell(socketTimeoutTextField)
                .bindIntText(property)
                .resizableColumn()
                .align(Align.FILL)
                .validationOnInput { isInt(it.text) }
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
