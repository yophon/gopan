package xyz.yophon.gopan.feature.textedit

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.data.text.TextFile
import xyz.yophon.gopan.core.data.text.TextFilesRepository
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.MainDispatcherRule

private class FakeTextFiles : TextFilesRepository {
    var file = TextFile("hello", "sha0", 5)
    var saveError: Throwable? = null
    /** 设上后保存要等它放行(模拟慢网络) */
    var saveGate: CompletableDeferred<Unit>? = null
    val saves = mutableListOf<Pair<String, String?>>()

    override suspend fun read(nodeId: String): TextFile = file

    override suspend fun save(nodeId: String, content: String, baseSha256: String?): String {
        saveGate?.await()
        saveError?.let {
            saveError = null
            throw it
        }
        saves += content to baseSha256
        return "sha${saves.size}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TextEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val texts = FakeTextFiles()

    private fun viewModel() = TextEditViewModel(
        SavedStateHandle(mapOf(TextEditViewModel.NODE_ID_ARG to "n1", TextEditViewModel.NAME_ARG to "笔记.md")),
        texts,
    )

    @Test
    fun `edits and saves with the sha from the last read or save`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.content).isEqualTo("hello")
        assertThat(vm.state.value.dirty).isFalse()

        vm.onChange("hello world")
        assertThat(vm.state.value.dirty).isTrue()
        vm.save()
        advanceUntilIdle()
        assertThat(texts.saves).containsExactly("hello world" to "sha0")
        assertThat(vm.state.value.dirty).isFalse()

        vm.onChange("third")
        vm.save()
        advanceUntilIdle()
        assertThat(texts.saves.last()).isEqualTo("third" to "sha1")
    }

    @Test
    fun `a conflict asks first, saving anyway skips the check`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onChange("mine")
        texts.saveError = GopanException(TextEditViewModel.EDIT_CONFLICT, "改过了")
        vm.save()
        advanceUntilIdle()
        assertThat(vm.state.value.conflict).isTrue()
        assertThat(vm.state.value.dirty).isTrue()

        vm.save(force = true)
        advanceUntilIdle()
        assertThat(texts.saves).containsExactly("mine" to null)
        assertThat(vm.state.value.conflict).isFalse()
    }

    @Test
    fun `too large files are not opened for editing`() = runTest {
        texts.file = TextFile("x", "s", TextEditViewModel.MAX_APP_EDIT + 1)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.loadError).isNotNull()
        vm.onChange("y")
        vm.save()
        advanceUntilIdle()
        assertThat(texts.saves).isEmpty()
    }

    @Test
    fun `leaving with unsaved changes asks first`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.effects.test {
            vm.requestClose()
            assertThat(awaitItem()).isEqualTo(TextEditEffect.Close)

            vm.onChange("changed")
            vm.requestClose()
            assertThat(vm.state.value.confirmLeave).isTrue()
            expectNoEvents()
            vm.discardAndClose()
            assertThat(awaitItem()).isEqualTo(TextEditEffect.Close)
        }
    }

    @Test
    fun `leaving while a save is in flight waits for it instead of asking`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        texts.saveGate = gate
        vm.effects.test {
            vm.onChange("changed")
            vm.save()
            advanceUntilIdle()
            vm.requestClose()
            assertThat(vm.state.value.confirmLeave).isFalse()
            expectNoEvents()

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(TextEditEffect.Message::class.java)
            assertThat(awaitItem()).isEqualTo(TextEditEffect.Close)
            assertThat(vm.state.value.confirmLeave).isFalse()
        }
    }
}
