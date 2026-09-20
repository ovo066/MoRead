package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ReaderTapAction

internal fun handleReaderTapAction(action: ReaderTapAction, screen: ReaderScreenState, reader: ReaderViewModel) {
    when (action) {
        ReaderTapAction.MENU -> screen.chromeVisible = !screen.chromeVisible
        ReaderTapAction.CONTENTS -> screen.activeSheet = ReaderSheet.CONTENTS
        ReaderTapAction.BOOKMARKS -> screen.activeSheet = ReaderSheet.BOOKMARKS
        ReaderTapAction.SETTINGS -> screen.activeSheet = ReaderSheet.SETTINGS
        ReaderTapAction.SEARCH -> screen.activeSheet = ReaderSheet.SEARCH
        ReaderTapAction.TOGGLE_BOOKMARK -> reader.toggleBookmark()
        ReaderTapAction.PREVIOUS_CHAPTER -> reader.goToPrevChapter()
        ReaderTapAction.NEXT_CHAPTER -> reader.goToNextChapter()
        ReaderTapAction.TOGGLE_TRANSLATIONS -> reader.toggleBilingualVisible()
        ReaderTapAction.ENGLISH_LEARNING -> { screen.dictionaryHit = null; screen.activeSheet = ReaderSheet.ENGLISH_LEARNING }
        else -> Unit // page turns belong to the paginated/scroll surface
    }
}
