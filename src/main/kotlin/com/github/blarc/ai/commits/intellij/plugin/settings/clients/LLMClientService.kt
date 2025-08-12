package com.github.blarc.ai.commits.intellij.plugin.settings.clients

import com.github.blarc.ai.commits.intellij.plugin.AICommitsBundle.message
import com.github.blarc.ai.commits.intellij.plugin.AICommitsUtils.computeDiff
import com.github.blarc.ai.commits.intellij.plugin.AICommitsUtils.constructPrompt
import com.github.blarc.ai.commits.intellij.plugin.AICommitsUtils.getCommonBranch
import com.github.blarc.ai.commits.intellij.plugin.notifications.Notification
import com.github.blarc.ai.commits.intellij.plugin.notifications.sendNotification
import com.github.blarc.ai.commits.intellij.plugin.settings.AppSettings2
import com.github.blarc.ai.commits.intellij.plugin.settings.ProjectSettings
import com.github.blarc.ai.commits.intellij.plugin.wrap
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBLabel
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcs.commit.isAmendCommitMode
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*

abstract class LLMClientService<C : LLMClientConfiguration>(private val cs: CoroutineScope) {

    var generateCommitMessageJob : Job? = null

    // This function should be implemented only by LLM services that can refresh models via API
    open suspend fun getAvailableModels(client: C): List<String>  {
        return listOf()
    }

    fun generateCommitMessage(clientConfiguration: C, commitWorkflowHandler: AbstractCommitWorkflowHandler<*, *>, project: Project) {

        val commitContext = commitWorkflowHandler.workflow.commitContext
        val includedChanges = commitWorkflowHandler.ui.getIncludedChanges().toMutableList()

        generateCommitMessageJob = cs.launch(ModalityState.current().asContextElement()) {
            withBackgroundProgress(project, message("action.background")) {

                if (commitContext.isAmendCommitMode) {
                    includedChanges += getLastCommitChanges(project)
                }

                val diff = computeDiff(includedChanges, false, project)
                if (diff.isBlank()) {
                    withContext(Dispatchers.EDT) {
                        sendNotification(Notification.emptyDiff())
                    }
                    return@withBackgroundProgress
                }

                val branch = getCommonBranch(includedChanges, project)
                val prompt = constructPrompt(project.service<ProjectSettings>().activePrompt.content, diff, branch, commitWorkflowHandler.getCommitMessage(), project)

                makeRequest(clientConfiguration, prompt, onSuccess = {
                    withContext(Dispatchers.EDT) {
                        clientConfiguration.setCommitMessage(commitWorkflowHandler, prompt, it)
                    }
                    AppSettings2.instance.recordHit()
                }, onError = {
                    withContext(Dispatchers.EDT) {
                        commitWorkflowHandler.setCommitMessage(it)
                    }
                })
            }
        }
    }

    fun verifyConfiguration(client: C, label: JBLabel) {
        label.text = message("settings.verify.running")
        label.icon = AllIcons.General.InlineRefresh
        cs.launch(ModalityState.current().asContextElement()) {
            makeRequest(client, "test", onSuccess = {
                withContext(Dispatchers.EDT) {
                    label.text = message("settings.verify.valid")
                    label.icon = AllIcons.General.InspectionsOK
                }
            }, onError = {
                withContext(Dispatchers.EDT) {
                    label.text = it.wrap(60)
                    label.icon = AllIcons.General.InspectionsError
                }
            })
        }
    }

    open suspend fun makeRequestWithTryCatch(function: suspend () -> Unit, onError: suspend (r: String) -> Unit) {
        try {
            function()
        } catch (e: IllegalArgumentException) {
            onError(message("settings.verify.invalid", e.message ?: message("unknown-error")))
        } catch (e: Exception) {
            onError(e.message ?: message("unknown-error"))
            // Generic exceptions should be logged by the IDE for easier error reporting
//            throw e
        }
    }

    abstract suspend fun makeRequest(client: C, text: String, onSuccess: suspend (r: String) -> Unit, onError: suspend (r: String) -> Unit)

    private suspend fun getLastCommitChanges(project: Project): List<Change> {
        return withContext(Dispatchers.IO) {
            GitRepositoryManager.getInstance(project).repositories.map { repo ->
                GitHistoryUtils.history(project, repo.root, "--max-count=1")
            }.filter { commits ->
                commits.isNotEmpty()
            }.map { commits ->
                (commits.first() as GitCommit).changes
            }.flatten()
        }
    }
}
