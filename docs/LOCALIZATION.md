# Localization

MoRead uses Android string resources for incremental localization. The default README is English; the app itself is still primarily Chinese. This foundation does **not** mean the entire app is translated, and no in-app language picker is exposed yet.

## Current scope

- [Default resources](../app/src/main/res/values/strings.xml) contain Simplified Chinese and must include every resource key.
- [English resources](../app/src/main/res/values-en/strings.xml) cover the app name, book long-press actions and reading states, collection counts and drop cues, the pinned-section warning, bookmark gestures/results, and selected import-progress messages.
- Android selects resources from the system locale configuration. Missing translations use the default resources. Existing hard-coded Chinese elsewhere in the app has not been silently replaced or advertised as translated.
- Reading content, titles, character cards, user-entered notes, persisted enum names, and AI prompts are not UI translations. Do not translate these as part of a resource migration.

## Adding or migrating text

1. Migrate one coherent UI flow at a time. Add stable, feature-prefixed keys such as `reader_bookmark_added` or `book_action_edit` to the default file, then provide matching English keys. Prefer translating complete messages over concatenating fragments.
2. In Compose, use `stringResource` and `pluralStringResource`. This allows configuration changes to refresh the text without rebuilding app state:

```kotlin
Text(stringResource(R.string.reader_bookmark_release))
Text(pluralStringResource(R.plurals.shelf_collection_book_count, count, count))
```

3. Outside Compose, use the current `Context`/`Resources`. For transient ViewModel events, prefer a resource ID with typed formatting arguments and resolve it in the UI; do not cache a localized string in long-lived state. The reader’s `ReaderEvent.ShowLocalizedMessage` is the current no-argument example.
4. Use indexed placeholders (`%1$d`, `%1$s`) so translators can reorder arguments. Preserve their types and indices across locales; escape a literal percent as `%%`. For a plural, pass the count both as the quantity selector and as a formatting argument.
5. Use `<plurals>` for quantities. English needs `one` and `other`; Chinese uses `other`. When adding a language, provide the plural categories it requires. Keep units inside the translated message.
6. Include accessibility descriptions, error messages, and progress cues—not just visible button labels. Avoid resource IDs or localized labels as database values or business-logic identifiers.
7. Follow Android XML escaping rules (`&amp;`, `&lt;`, and escaped apostrophes where needed). Mark true non-translatable identifiers with `translatable="false"` instead of duplicating them in each locale.

New languages belong in standard Android locale-qualified resource directories. Do not add a language-selection UI or declare comprehensive language support until its main flows, dialogs, accessibility labels, and settings have actually been translated and verified.

## Verification

Run:

```sh
./gradlew :app:testDebugUnitTest
```

[LocalizationResourcesTest](../app/src/test/java/com/mozhi/reader/core/LocalizationResourcesTest.kt) checks English selection, default-resource fallback, indexed arguments, percentages, and plural behavior with Android resources under Robolectric. Extend it for new formatting patterns. Resource compilation also catches malformed XML and invalid format strings.

For each migrated flow, check Chinese and English on a narrow phone and a short/landscape window, with a larger font scale. Confirm that menus remain reachable, touch targets stay at least 44dp, counts and percentages are correct, and translated text is not clipped. JVM tests complement—but do not replace—testing on a device.

Book-menu layout and pointer regressions live in `BookLongPressOverlayTest` and `ReaderPageTouchTest`. Pull-to-bookmark must remain disabled in continuous vertical scrolling mode regardless of locale.

## Documentation

Keep [README.md](../README.md) and [README.zh-CN.md](../README.zh-CN.md) aligned when changing features, setup steps, privacy statements, or limitations. The English README must continue to disclose partial app localization until a complete English UI is available. Update the public [code map](CODE_MAP.md) when moving resource-related entry points.
