/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.lifecycle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*


/**
 * Lifecycle tasks used to to fan out the build into multiple builds in a CI pipeline.
 */
class LifecyclePlugin : Plugin<Project> {

    private
    val compileAllBuild = "compileAllBuild"
    private
    val platformTest = "platformTest"
    private
    val allVersionsIntegMultiVersionTest = "allVersionsIntegMultiVersionTest"
    private
    val quickFeedbackCrossVersionTest = "quickFeedbackCrossVersionTest"
    private
    val allVersionsCrossVersionTest = "allVersionsCrossVersionTest"
    private
    val soakTest = "soakTest"
    private
    val noDaemonTest = "noDaemonTest"

    private
    val ciGroup = "CI Lifecycle"

    override fun apply(project: Project): Unit = project.run {
        setupGlobalState()

        subprojects {
            plugins.withId("gradlebuild.java-projects") {
                tasks.registerEarlyFeedbackLifecycleTasks()
            }
            plugins.withId("gradlebuild.integration-tests") {
                tasks.registerCIIntegrationTestDistributionLifecycleTasks()
            }
            plugins.withId("gradlebuild.cross-version-tests") {
                tasks.registerCICrossVersionTestDistributionLifecycleTasks()
            }
        }
    }

    private
    fun Project.setupGlobalState(): Unit = project.run {
        if (needsToIgnoreIncomingBuildReceipt()) {
            globalProperty("ignoreIncomingBuildReceipt" to true)
        }
        if (needsToUseTestVersionsPartial()) {
            globalProperty("testVersions" to "partial")
        }
        if (needsToUseTestVersionsAll()) {
            globalProperty("testVersions" to "all")
        }
        if (needsToUseAllDistribution()) {
            globalProperty("useAllDistribution" to true)
        }
    }

    private
    fun Project.needsToIgnoreIncomingBuildReceipt() = isRequestedTask(compileAllBuild)

    private
    fun Project.needsToUseTestVersionsPartial() = isRequestedTask(platformTest)

    private
    fun Project.needsToUseTestVersionsAll() = isRequestedTask(allVersionsCrossVersionTest)
        || isRequestedTask(allVersionsIntegMultiVersionTest)
        || isRequestedTask(soakTest)

    private
    fun Project.needsToUseAllDistribution() = isRequestedTask(quickFeedbackCrossVersionTest)
        || isRequestedTask(allVersionsCrossVersionTest)
        || isRequestedTask(allVersionsIntegMultiVersionTest)
        || isRequestedTask(noDaemonTest)

    /**
     * Basic compile and check lifecycle tasks.
     */
    private
    fun TaskContainer.registerEarlyFeedbackLifecycleTasks() {
        register(compileAllBuild) {
            description = "Initialize CI Pipeline by priming the cache before fanning out"
            group = ciGroup
            dependsOn(":createBuildReceipt", "compileAll")
        }

        register("sanityCheck") {
            description = "Run all basic checks (without tests) - to be run locally and on CI for early feedback"
            group = "verification"
            dependsOn(
                "compileAll", ":docs:checkstyleApi", "codeQuality", ":allIncubationReportsZip",
                ":distributions:checkBinaryCompatibility", ":docs:javadocAll",
                ":architectureTest:test", ":toolingApi:toolingApiShadedJar")
        }
    }

    /**
     * Integration test lifecycle tasks that correspond to CIBuildModel.TestType (see .teamcity/Gradle_Check/model/CIBuildModel.kt).
     */
    private
    fun TaskContainer.registerCIIntegrationTestDistributionLifecycleTasks() {
        register("quickTest") {
            description = "Run all unit, integration and cross-version (against latest release) tests in embedded execution mode"
            group = ciGroup
            dependsOn("test", "integTest", "crossVersionTest")
        }

        register(platformTest) {
            description = "Run all unit, integration and cross-version (against latest release) tests in forking execution mode"
            group = ciGroup
            dependsOn("test", "forkingIntegTest", "forkingCrossVersionTest")
        }

        register(allVersionsIntegMultiVersionTest) {
            description = "Run all multi-version integration tests with all version to cover"
            group = ciGroup
            dependsOn("integMultiVersionTest")
        }

        register("parallelTest") {
            description = "Run all integration tests in parallel execution mode: each Gradle execution started in a test run with --parallel"
            group = ciGroup
            dependsOn("parallelIntegTest")
        }

        register(noDaemonTest) {
            description = "Run all integration tests in no-daemon execution mode: each Gradle execution started in a test forks a new daemon"
            group = ciGroup
            dependsOn("noDaemonIntegTest")
        }

        register("instantTest") {
            description = "Run all integration tests with instant execution"
            group = ciGroup
            dependsOn("instantIntegTest")
        }

        register("vfsRetentionTest") {
            description = "Run all integration tests with vfs retention enabled"
            group = ciGroup
            dependsOn("vfsRetentionIntegTest")
        }

        register(soakTest) {
            description = "Run all soak tests defined in the :soak subproject"
            group = ciGroup
            dependsOn(":soak:soakIntegTest")
        }

        register("forceRealizeDependencyManagementTest") {
            description = "Runs all integration tests with the dependency management engine in 'force component realization' mode"
            group = ciGroup
            dependsOn("integForceRealizeTest")
        }
    }

    /**
     * Cross-version test lifecycle tasks that correspond to CIBuildModel.TestType (see .teamcity/Gradle_Check/model/CIBuildModel.kt).
     */
    private
    fun TaskContainer.registerCICrossVersionTestDistributionLifecycleTasks() {
        register(quickFeedbackCrossVersionTest) {
            description = "Run cross-version tests against a limited set of versions"
            group = ciGroup
            dependsOn("quickFeedbackCrossVersionTests")
        }

        register(allVersionsCrossVersionTest) {
            description = "Run cross-version tests against all released versions (latest patch release of each)"
            group = ciGroup
            dependsOn("allVersionsCrossVersionTests")
        }
    }

    private
    fun Project.globalProperty(pair: Pair<String, Any>) {
        val propertyName = pair.first
        val value = pair.second
        if (hasProperty(propertyName)) {
            val otherValue = property(propertyName)
            if (value.toString() != otherValue.toString()) {
                throw RuntimeException("Attempting to set global property $propertyName to two different values ($value vs $otherValue)")
            }
        }
        extra.set(propertyName, value)
    }

    private
    fun Project.isRequestedTask(taskName: String) = gradle.startParameter.taskNames.contains(taskName)
}
