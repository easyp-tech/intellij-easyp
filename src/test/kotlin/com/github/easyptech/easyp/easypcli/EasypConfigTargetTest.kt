package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths

class EasypConfigTargetTest : BasePlatformTestCase() {
    fun testDefaultConfigPathResolvesToProjectRoot() {
        val settings = EasypSettings.getInstance(project).state
        settings.configPath = null

        val resolved = EasypConfigTarget.resolvedConfigPath(project, settings)
        val expected = Paths.get(project.basePath!!).resolve("easyp.yaml").normalize()

        assertEquals(expected, resolved)
    }

    fun testRelativeConfigPathResolvesAgainstProjectRoot() {
        val settings = EasypSettings.getInstance(project).state
        settings.configPath = "api/proto/easyp.yaml"

        val resolved = EasypConfigTarget.resolvedConfigPath(project, settings)
        val expected = Paths.get(project.basePath!!).resolve("api/proto/easyp.yaml").normalize()

        assertEquals(expected, resolved)
    }

    fun testAbsoluteConfigPathMatchesStrictly() {
        val target = myFixture.tempDirFixture.createFile("configs/custom-easyp.yaml", "version: v1alpha\n")
        val defaultFile = myFixture.tempDirFixture.createFile("easyp.yaml", "version: v1alpha\n")
        val settings = EasypSettings.getInstance(project).state
        settings.configPath = target.path

        assertTrue(EasypConfigTarget.isTargetConfigFile(target, project, settings))
        assertFalse(EasypConfigTarget.isTargetConfigFile(defaultFile, project, settings))
    }

    fun testRelativeOverrideDoesNotMatchDefaultEasypYaml() {
        val overrideConfig = myFixture.tempDirFixture.createFile("api/proto/custom-easyp.yaml", "version: v1alpha\n")
        val defaultFile = myFixture.tempDirFixture.createFile("easyp.yaml", "version: v1alpha\n")
        val settings = EasypSettings.getInstance(project).state
        settings.configPath = "api/proto/custom-easyp.yaml"

        assertTrue(EasypConfigTarget.isTargetConfigFile(overrideConfig, project, settings))
        assertFalse(EasypConfigTarget.isTargetConfigFile(defaultFile, project, settings))
    }
}
