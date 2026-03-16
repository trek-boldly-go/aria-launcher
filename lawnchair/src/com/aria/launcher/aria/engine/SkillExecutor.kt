// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.SkillResult

interface SkillExecutor {
    val supportedSkillIds: Set<String>
    suspend fun execute(skill: AppSkill): SkillResult?
}

class SkillExecutorRegistry(
    private val executors: List<SkillExecutor>,
) {
    private val executorMap: Map<String, SkillExecutor> by lazy {
        executors.flatMap { executor ->
            executor.supportedSkillIds.map { id -> id to executor }
        }.toMap()
    }

    fun getExecutor(skillId: String): SkillExecutor? = executorMap[skillId]

    fun allSkillIds(): Set<String> = executorMap.keys
}
