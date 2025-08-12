package com.github.blarc.ai.commits.intellij.plugin

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import javax.swing.Icon

object Icons {

    data class AICommitsIcon(val bright: String, val dark: String?) {

        fun getThemeBasedIcon(): Icon {
            return if (JBColor.isBright() || dark == null) {
                IconLoader.getIcon(bright, javaClass)
            } else {
                IconLoader.getIcon(dark, javaClass)
            }
        }
    }

    val AI_COMMITS = AICommitsIcon("/icons/aiCommits15.svg", null)
    val HUGGING_FACE = AICommitsIcon("/icons/huggingface.svg", null)
    val CLAUDE = AICommitsIcon("/icons/claude-ai-icon.svg", "/icons/claude-ai-icon.svg")

    object Process {
        val STOP = AICommitsIcon("/icons/stop.svg", "/icons/stop_dark.svg")
    }
}
