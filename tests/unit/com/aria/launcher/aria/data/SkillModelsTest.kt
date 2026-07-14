package com.aria.launcher.aria.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkillModelsTest {

    @Test
    fun `TriggerType enum values`() {
        assertThat(TriggerType.values().toList())
            .containsExactly(TriggerType.PROACTIVE, TriggerType.ON_DEMAND, TriggerType.SCHEDULED)
    }

    @Test
    fun `SkillSourceType enum values`() {
        assertThat(SkillSourceType.values().toList())
            .containsExactly(
                SkillSourceType.BUILT_IN,
                SkillSourceType.SHORTCUT,
                SkillSourceType.INTENT,
                SkillSourceType.MCP,
                SkillSourceType.ACCESSIBILITY,
                SkillSourceType.EXTERNAL_HTTP,
            )
    }

    @Test
    fun `AppSkill default enabled is true`() {
        val skill = AppSkill(
            id = "gmail.inbox_summary",
            appPackage = "com.google.android.gm",
            name = "Inbox Summary",
            description = "Shows unread count",
            triggerType = TriggerType.PROACTIVE.name,
            sourceType = SkillSourceType.BUILT_IN.name,
            contextMatch = "WEEKDAY_MORNING_HOME",
            refreshIntervalMin = 15,
        )
        assertThat(skill.enabled).isTrue()
    }

    @Test
    fun `AppSkill can be disabled`() {
        val skill = AppSkill(
            id = "test.skill",
            appPackage = "com.test",
            name = "Test",
            description = "Test skill",
            triggerType = TriggerType.ON_DEMAND.name,
            sourceType = SkillSourceType.INTENT.name,
            contextMatch = "",
            refreshIntervalMin = 0,
            enabled = false,
        )
        assertThat(skill.enabled).isFalse()
    }

    @Test
    fun `SkillResult construction with nullable expiresAt`() {
        val result = SkillResult(
            skillId = "gmail.inbox_summary",
            title = "Gmail - 3 new",
            body = "You have 3 unread emails",
            actions = "[]",
            priority = 0.8f,
            timestamp = 1000L,
            expiresAt = null,
        )
        assertThat(result.expiresAt).isNull()
        assertThat(result.priority).isEqualTo(0.8f)
    }

    @Test
    fun `SkillResult with expiry`() {
        val result = SkillResult(
            skillId = "weather.current",
            title = "Weather",
            body = "72F and sunny",
            actions = """[{"label":"Open","type":"OPEN_APP","payload":"com.weather"}]""",
            priority = 0.5f,
            timestamp = 1000L,
            expiresAt = 2000L,
        )
        assertThat(result.expiresAt).isEqualTo(2000L)
    }

    @Test
    fun `SkillAction data class construction`() {
        val action = SkillAction(
            label = "Reply",
            type = "DEEP_LINK",
            payload = "mailto:test@example.com",
        )
        assertThat(action.label).isEqualTo("Reply")
        assertThat(action.type).isEqualTo("DEEP_LINK")
        assertThat(action.payload).isEqualTo("mailto:test@example.com")
    }

    @Test
    fun `AppSkill copy preserves fields`() {
        val original = AppSkill(
            id = "test.skill",
            appPackage = "com.test",
            name = "Test",
            description = "Test skill",
            triggerType = TriggerType.SCHEDULED.name,
            sourceType = SkillSourceType.MCP.name,
            contextMatch = "WEEKDAY_MORNING_HOME,WEEKDAY_AFTERNOON_WORK",
            refreshIntervalMin = 30,
        )
        val disabled = original.copy(enabled = false)
        assertThat(disabled.id).isEqualTo(original.id)
        assertThat(disabled.name).isEqualTo(original.name)
        assertThat(disabled.enabled).isFalse()
    }
}
