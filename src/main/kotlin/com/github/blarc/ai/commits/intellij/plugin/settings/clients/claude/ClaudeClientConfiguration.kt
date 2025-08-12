package com.github.blarc.ai.commits.intellij.plugin.settings.clients.claude

import com.github.blarc.ai.commits.intellij.plugin.Icons
import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientConfiguration
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import kotlinx.coroutines.Job
import javax.swing.Icon

class ClaudeClientConfiguration : LLMClientConfiguration(
    CLIENT_NAME
) {

    @get:JvmName("getCommandProperty")
    @Attribute
    var command: String = "claude"

    @get:JvmName("getPathProperty")
    @Attribute
    var path: String? = null

    @get:JvmName("getTimeoutProperty")
    @Attribute
    var timeout: Int = 60

    companion object {
        const val CLIENT_NAME = "Claude Cli"
    }

    override fun getClientName(): String {
        return CLIENT_NAME
    }

    override fun getClientIcon(): Icon {
        return Icons.CLAUDE.getThemeBasedIcon()
    }

    override fun getPath(): String = path ?: ""

    override fun getCommand(): String = command

    override fun getTimeout(): Int = timeout

    override fun generateCommitMessage(commitWorkflowHandler: AbstractCommitWorkflowHandler<*, *>, project: Project) {
        return ClaudeClientService.Companion.getInstance().generateCommitMessage(this, commitWorkflowHandler, project)
    }

    override fun getGenerateCommitMessageJob(): Job? {
        return ClaudeClientService.Companion.getInstance().generateCommitMessageJob
    }

    override fun clone(): LLMClientConfiguration {
        val copy = ClaudeClientConfiguration()
        copy.id = id
        copy.name = name
        copy.path = path
        copy.command = command
        copy.timeout = timeout
        return copy
    }

    override fun panel() = ClaudeClientPanel(this)
}
