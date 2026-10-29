package xyz.yophon.gopan.feature.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.assistant.AiProvider

class AssistantLogicTest {
    private fun provider(id: String, vararg models: String, site: Boolean = false) =
        AiProvider(id, "P$id", "OPENAI", "https://x/v1", models.toList(), hasKey = true, site = site, dailyTokens = 0, usedTokens = 0)

    @Test
    fun picksRememberedProviderAndModelOrFallsBack() {
        val list = listOf(provider("a", "m1", "m2", site = true), provider("b", "x"))
        assertThat(AssistantLogic.resolvePick(list, "b|x")?.let { it.first.id to it.second }).isEqualTo("b" to "x")
        // 模型不在了:用这个服务的默认模型
        assertThat(AssistantLogic.resolvePick(list, "a|gone")?.second).isEqualTo("m1")
        // 服务不在了 / 没选过:用第一个
        assertThat(AssistantLogic.resolvePick(list, "zzz|x")?.first?.id).isEqualTo("a")
        assertThat(AssistantLogic.resolvePick(list, "")?.first?.id).isEqualTo("a")
        assertThat(AssistantLogic.resolvePick(emptyList(), "a|m1")).isNull()
    }

    @Test
    fun contextMatchesTheWebWording() {
        val text = AssistantLogic.contextText("/旅行", "f1", listOf(AssistantLogic.Selected("n1", "1.jpg", folder = false)))
        assertThat(text).isEqualTo("用户正在看:/旅行(文件夹 id f1)\n选中了 1 项:\n- 1.jpg(文件,id n1)")
        assertThat(AssistantLogic.contextText("/", null, emptyList())).isEqualTo("用户正在看:/(根目录)")
        assertThat(AssistantLogic.contextLabel("/", 0)).isEqualTo("我的文件")
        assertThat(AssistantLogic.contextLabel("/旅行", 3)).isEqualTo("/旅行 · 选中 3 项")
    }

    @Test
    fun parsesModelsOnePerLine() {
        assertThat(AssistantLogic.parseModels(" deepseek-flash \n\ndeepseek-v4-pro, deepseek-flash")).containsExactly("deepseek-flash", "deepseek-v4-pro").inOrder()
    }

    @Test
    fun rendersCommonMarkdown() {
        val s = miniMarkdown("已完成:\n- 新建 `/2026 报销`\n- 移入 **2** 个文件\n```\n**原样**\n```")
        assertThat(s.text).isEqualTo("已完成:\n•  新建 /2026 报销\n•  移入 2 个文件\n**原样**")
        assertThat(s.spanStyles).hasSize(3)
    }
}
