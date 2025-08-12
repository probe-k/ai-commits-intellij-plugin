package com.github.blarc.ai.commits.intellij.plugin.settings.clients.custom;

import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientPanel
import com.intellij.ui.dsl.builder.*

class CustomCliPanel private constructor(
    private val clientConfiguration: CustomCliConfiguration,
    val service: CustomCliService
) : LLMClientPanel(clientConfiguration) {

    constructor(configuration: CustomCliConfiguration) : this(configuration, CustomCliService.Companion.getInstance())

    override fun create() = panel {
        nameRow()
        pathRow(clientConfiguration::path)
        commandRow(clientConfiguration::command)
        timeoutRow(clientConfiguration::timeout)
        verifyRow()

    }

    override fun verifyConfiguration() {
        // Configuration passed to panel is already a copy of the original or a new configuration
        clientConfiguration.path = pathTextField.text
        clientConfiguration.timeout = socketTimeoutTextField.text.toInt()
        service.verifyConfiguration(clientConfiguration, verifyLabel)
    }
}
