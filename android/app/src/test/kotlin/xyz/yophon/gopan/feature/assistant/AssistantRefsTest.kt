package xyz.yophon.gopan.feature.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.assistant.AssistantItem
import xyz.yophon.gopan.core.assistant.withKnownImages

/** 回答里引用的文件、随消息发的图(doc/35),与网页 files.test.ts 同几条 */
class AssistantRefsTest {
    private val a = "0190f1a2-0000-7000-8000-00000000000a"
    private val b = "0190f1a2-0000-7000-8000-00000000000b"

    @Test
    fun onlyRefParagraphsBecomeCards() {
        val text = "找到了 [报销](gopan:$a) 文件夹,里面有:\n\n- [发票1.jpg](gopan:$b)\n- [发票2.jpg](gopan:${a.uppercase()})\n\n要移动吗?"
        assertThat(AssistantLogic.refsIn(text).map { it.id }).containsExactly(a, b, a).inOrder()
        val blocks = AssistantLogic.splitReply(text)
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Md", "Cards", "Md").inOrder()
        assertThat((blocks[1] as AssistantLogic.ReplyBlock.Cards).refs)
            .containsExactly(AssistantLogic.FileRef("发票1.jpg", b), AssistantLogic.FileRef("发票2.jpg", a)).inOrder()
    }

    @Test
    fun pathsWorkWhenTheModelHasNoId() {
        assertThat(AssistantLogic.refsIn("改好了 [todo.md](gopan:/笔记/todo.md) 和 [笔记](gopan:/笔记/)"))
            .containsExactly(AssistantLogic.FileRef("todo.md", "/笔记/todo.md"), AssistantLogic.FileRef("笔记", "/笔记")).inOrder()
    }

    @Test
    fun codeBlocksAndOtherSchemesAreLeftAlone() {
        assertThat(AssistantLogic.splitReply("```\na\n\nb\n```")).containsExactly(AssistantLogic.ReplyBlock.Md("```\na\n\nb\n```"))
        assertThat(AssistantLogic.refsIn("[x](https://e.com) [y](gopan:not-a-uuid)")).isEmpty()
    }

    @Test
    fun inlineRefsGoThroughTheCallbackEvenInsideBold() {
        val seen = mutableListOf<String>()
        val text = miniMarkdown("看 **[报告.pdf](gopan:$a)** 和 [笔记](gopan:$b)") { ref -> seen += ref.id; append("<${ref.name}>") }
        assertThat(text.text).isEqualTo("看 <报告.pdf> 和 <笔记>")
        assertThat(seen).containsExactly(a, b).inOrder()
        // 没给回调:只写名字
        assertThat(miniMarkdown("[笔记](gopan:$b)").text).isEqualTo("笔记")
    }

    @Test
    fun pushedItemsKeepTheImagesAlreadyOnScreen() {
        val jpg = "data:image/jpeg;base64,AAAA"
        val current = listOf(AssistantItem("user", "pending-0", "看看", images = listOf(jpg)))
        val pushed = listOf(
            AssistantItem("user", "u0", "看看", imageCount = 1),
            AssistantItem("assistant", "a1", "是发票"),
            AssistantItem("user", "u2", "再来", imageCount = 2),
        )
        val merged = withKnownImages(pushed, current)
        assertThat(merged[0].images).containsExactly(jpg)
        assertThat(merged[1]).isEqualTo(pushed[1])
        assertThat(merged[2].images).isNull() // 不认识的那两张:只显示张数
    }
}
