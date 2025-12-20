package com.github.yakwilik.intellijeasyp.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service

@Service(Service.Level.PROJECT)
@State(name = "EasypSettings", storages = [Storage("easyp_settings.xml")])
class EasypSettings(private val project: Project) : PersistentStateComponent<EasypSettings.State> {
    data class State(
        var easypCliPath: String? = null,
        var configPath: String? = null,
        var enableProtoImportResolve: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): EasypSettings = project.service()
    }
}
