package com.github.blarc.ai.commits.intellij.plugin.settings.clients.claude;

import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientPanel
import com.intellij.ui.dsl.builder.*

class ClaudeClientPanel private constructor(
    private val clientConfiguration: ClaudeClientConfiguration,
    val service: ClaudeClientService
) : LLMClientPanel(clientConfiguration) {

    constructor(configuration: ClaudeClientConfiguration) : this(configuration, ClaudeClientService.Companion.getInstance())

    override fun create() = panel {
        nameRow()
        pathRow(clientConfiguration::path)
        timeoutRow(clientConfiguration::timeout)
        verifyRow()
    }

    override fun verifyConfiguration() {
        // Configuration passed to panel is already a copy of the original or a new configuration
        clientConfiguration.timeout = socketTimeoutTextField.text.toInt()
        service.verifyConfiguration(clientConfiguration, verifyLabel)
    }
}
