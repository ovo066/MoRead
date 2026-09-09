package com.mozhi.reader.feature.reader

/** One immutable session value; old screen callbacks cannot edit the next persona's draft. */
internal data class CompanionComposerDraft(val personaId: Long?, val text: String = "") {
    fun edit(owner: Long?, text: String): CompanionComposerDraft =
        if (owner == personaId) copy(text = text) else this

    fun visibleTo(owner: Long?): String = text.takeIf { owner == personaId }.orEmpty()
}
