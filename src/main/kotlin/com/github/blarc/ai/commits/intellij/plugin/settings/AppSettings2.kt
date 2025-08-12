package com.github.blarc.ai.commits.intellij.plugin.settings

import com.github.blarc.ai.commits.intellij.plugin.AICommitsUtils
import com.github.blarc.ai.commits.intellij.plugin.notifications.Notification
import com.github.blarc.ai.commits.intellij.plugin.notifications.sendNotification
import com.github.blarc.ai.commits.intellij.plugin.settings.clients.LLMClientConfiguration
import com.github.blarc.ai.commits.intellij.plugin.settings.clients.claude.ClaudeClientConfiguration
import com.github.blarc.ai.commits.intellij.plugin.settings.clients.custom.CustomCliConfiguration
import com.github.blarc.ai.commits.intellij.plugin.settings.prompts.DefaultPrompts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.Converter
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap
import java.util.*

@State(
    name = AppSettings2.SERVICE_NAME,
    storages = [
        Storage("AICommits2.xml")
    ]
)
@Service(Service.Level.APP)
class AppSettings2 : PersistentStateComponent<AppSettings2> {

    companion object {
        const val SERVICE_NAME = "com.github.blarc.ai.commits.intellij.plugin.settings.AppSettings2"
        val instance: AppSettings2
            get() = ApplicationManager.getApplication().getService(AppSettings2::class.java)
    }

    private var hits = 0
    var requestSupport = true
    var lastVersion: String? = null

    @OptionTag(converter = LocaleConverter::class)
    var locale: Locale = Locale.KOREAN

    @XCollection(
        elementTypes = [
            ClaudeClientConfiguration::class,
//            CustomCliConfiguration::class
        ],
        style = XCollection.Style.v2
    )
    var llmClientConfigurations = setOf<LLMClientConfiguration>(
        ClaudeClientConfiguration(),
//        CustomCliConfiguration()
    )

    @Attribute
    var activeLlmClientId: String? = null

    @XMap
    var prompts = DefaultPrompts.toPromptsMap()
    var activePrompt = DefaultPrompts.BASIC.prompt
    var promptDialogWidth = 800
    var promptDialogHeight = 600

    var appExclusions: Set<String> = setOf()

    @Attribute
    var useStreamingResponse: Boolean = true

    override fun getState() = this

    override fun loadState(state: AppSettings2) {
        XmlSerializerUtil.copyBean(state, this)
    }

    override fun noStateLoaded() {
        val appSettings = AppSettings.instance
//        migrateSettingsFromVersion1(appSettings)
    }

    fun recordHit() {
        hits++
        if (requestSupport && (hits == 50 || hits % 100 == 0)) {
            sendNotification(Notification.star())
        }
    }

    fun isPathExcluded(path: String): Boolean {
        return AICommitsUtils.matchesGlobs(path, appExclusions)
    }

    fun getActiveLLMClientConfiguration(): LLMClientConfiguration? {
        return getActiveLLMClientConfiguration(activeLlmClientId)
    }

    fun getActiveLLMClientConfiguration(activeLLMClientConfigurationId: String?): LLMClientConfiguration? {
        return llmClientConfigurations.find { it.id == activeLLMClientConfigurationId }
            ?: llmClientConfigurations.firstOrNull()
    }

    class LocaleConverter : Converter<Locale>() {
        override fun toString(value: Locale): String? {
            return value.toLanguageTag()
        }

        override fun fromString(value: String): Locale? {
            return Locale.forLanguageTag(value)
        }
    }
}
