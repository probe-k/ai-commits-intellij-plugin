package com.github.blarc.ai.commits.intellij.plugin.settings.clients

import com.github.blarc.ai.commits.intellij.plugin.Icons
import com.github.blarc.ai.commits.intellij.plugin.notifications.Notification
import com.github.blarc.ai.commits.intellij.plugin.notifications.sendNotification
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import kotlinx.coroutines.Job
import java.util.*
import javax.swing.Icon

abstract class LLMClientConfiguration(
    @Attribute var name: String,
) : Cloneable, Comparable<LLMClientConfiguration>, AnAction() {

    @Attribute
    var id: String = UUID.randomUUID().toString()

    abstract fun getClientName(): String

    abstract fun getClientIcon(): Icon

    abstract fun getPath(): String

    abstract fun getCommand(): String

    abstract fun getTimeout(): Int

    open fun setCommitMessage(commitWorkflowHandler: AbstractCommitWorkflowHandler<*, *>, prompt: String, result: String) {
        commitWorkflowHandler.setCommitMessage(result)
    }

    abstract fun generateCommitMessage(commitWorkflowHandler: AbstractCommitWorkflowHandler<*, *>, project: Project)

    abstract fun getGenerateCommitMessageJob(): Job?

    public abstract override fun clone(): LLMClientConfiguration

    abstract fun panel(): LLMClientPanel

    override fun compareTo(other: LLMClientConfiguration): Int {
        return name.compareTo(other.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val generateCommitMessageJob = getGenerateCommitMessageJob()
        if (generateCommitMessageJob?.isActive == true) {
            generateCommitMessageJob.cancel()
            return
        }

        val commitWorkflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as AbstractCommitWorkflowHandler<*, *>?
        if (commitWorkflowHandler == null) {
            sendNotification(Notification.noCommitMessage())
            return
        }

        generateCommitMessage(commitWorkflowHandler, project)
    }

    override fun update(e: AnActionEvent) {

        if (getGenerateCommitMessageJob()?.isActive == true) {
            e.presentation.icon = Icons.Process.STOP.getThemeBasedIcon()
        } else {
            e.presentation.icon = getClientIcon()
            // e.presentation.text = message("action.tooltip", name)
            e.presentation.text = name
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

//    override fun equals(other: Any?): Boolean {
//        return other is LLMClientConfiguration && other.id == id
//    }
//
//    override fun hashCode(): Int {
//        return id.hashCode()
//    }
}
