package io.github.madeye.meow.ui.screens.yaml

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.madeye.meow.editor.SoraTextMateBootstrap
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

/**
 * Imperative handle onto the embedded editor.
 *
 * The editor owns the document — a config can be several megabytes, so it is
 * never mirrored into Compose state.
 */
@Stable
class SoraEditorHandle {
    internal var editor: CodeEditor? = null

    fun text(): String = editor?.text?.toString().orEmpty()

    fun setText(value: String) {
        editor?.setText(value)
    }

    fun undo() {
        editor?.undo()
    }
}

@Composable
fun rememberSoraEditorHandle(): SoraEditorHandle = remember { SoraEditorHandle() }

/**
 * Wraps sora-editor's [CodeEditor] directly.
 *
 * Previously this went through a Flutter `PlatformView` whose change callback
 * shipped the entire document across a MethodChannel on every keystroke — an
 * O(document) copy per character. Compose runs in the same process, so
 * [onContentChanged] carries no payload: it is a tick, and the debounced
 * validator pulls the text once when the user stops typing.
 */
@Composable
fun SoraYamlEditor(
    initialText: String,
    handle: SoraEditorHandle,
    onContentChanged: () -> Unit,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 14f,
) {
    val changed by rememberUpdatedState(onContentChanged)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SoraTextMateBootstrap.init(context.applicationContext)
            CodeEditor(context).apply {
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                setEditorLanguage(SoraTextMateBootstrap.yamlLanguage())
                setTextSize(textSizeSp)
                // Only here: re-setting the text on recomposition would reset
                // the caret and scroll position on every keystroke.
                setText(initialText)
                subscribeAlways<ContentChangeEvent> { changed() }
                handle.editor = this
            }
        },
        update = { /* deliberately empty — see the setText comment above */ },
        onRelease = { editor ->
            handle.editor = null
            // sora-editor keeps background analysis threads alive otherwise.
            editor.release()
        },
    )
}
