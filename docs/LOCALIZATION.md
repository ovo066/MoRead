# Localization

MoRead uses Android string resources for incremental localization. The default README is English; the app itself is still primarily Chinese. Simplified Chinese is the default language, and English is a partial translation that is being extended flow by flow.

## Current scope

- [Default resources](../app/src/main/res/values) contain Simplified Chinese and must include every resource key. Illustration-studio strings are grouped in `image_consistency.xml`.
- [English resources](../app/src/main/res/values-en) cover the illustration studio and DIY style editor, the app name, the bottom navigation and tablet sidebar, the settings home and its reading/appearance, AI/companion and about pages, the language picker, book long-press actions and reading states, collection counts and drop cues, the pinned-section warning, bookmark gestures/results, external font import, and selected import-progress messages.
- Missing translations fall back to the default (Chinese) resources, so an English interface is currently mixed. Theme preset names, the rest of the reader, companion chat, and most secondary settings pages are still hard-coded Chinese.
- Reading content, titles, character cards, user-entered notes, persisted enum names, and AI prompts are not UI translations. Do not translate these as part of a resource migration. AI prompts and tool results remain Chinese regardless of the interface language.

## Choosing the interface language

Settings → Reading & appearance → Language offers *System*, *简体中文* and *English*. The two language names are always shown in their own language (`translatable="false"`).

- Android 13+: the choice goes through the platform per-app language API (`LocaleManager`). [`res/xml/locales_config.xml`](../app/src/main/res/xml/locales_config.xml) declares the choices, so the same setting also appears under the system's app-language settings.
- Android 8–12: the choice is stored in a private `SharedPreferences` file. `MainActivity.attachBaseContext` wraps its context with the selected locale, and `MoReadApplication` applies it to application resources at startup and after configuration changes, so strings resolved through the application context (workers, notifications) follow as well.
- Both paths live in `core/i18n/AppLocales`; the activity is recreated after a change and navigation state is kept.

When adding a language, update `AppLanguage`, `locales_config.xml`, and add the `values-xx` directory together.

## Adding or migrating text

1. Migrate one coherent UI flow at a time. Add stable, feature-prefixed keys such as `reader_bookmark_added` or `book_action_edit` to the default file, then provide matching English keys. Prefer translating complete messages over concatenating fragments.
2. In Compose, use `stringResource` and `pluralStringResource`. This allows configuration changes to refresh the text without rebuilding app state:

```kotlin
Text(stringResource(R.string.reader_bookmark_release))
Text(pluralStringResource(R.plurals.shelf_collection_book_count, count, count))
```

3. Outside Compose, pass a `core/i18n/UiText` instead of a resolved string. It carries a resource ID with formatting arguments (arguments may themselves be `UiText`), a plural, or raw text that must not be translated (book titles, user input, server errors). Resolve it where it is displayed: `text.asString()` in Compose (`ui/components/UiTextResources`), `text.resolve(context)` elsewhere. Do not cache a localized string in long-lived state or persist it; it will not follow a language change. The reader’s `ReaderEvent.ShowLocalizedMessage(UiText)` is the reference example.
4. Use indexed placeholders (`%1$d`, `%1$s`) so translators can reorder arguments. Preserve their types and indices across locales; escape a literal percent as `%%`. For a plural, pass the count both as the quantity selector and as a formatting argument.
5. Use `<plurals>` for quantities. English needs `one` and `other`; Chinese uses `other`. When adding a language, provide the plural categories it requires. Keep units inside the translated message.
6. Include accessibility descriptions, error messages, and progress cues—not just visible button labels. Avoid resource IDs or localized labels as database values or business-logic identifiers.
7. Follow Android XML escaping rules (`&amp;`, `&lt;`, and escaped apostrophes where needed). Mark true non-translatable identifiers with `translatable="false"` instead of duplicating them in each locale.

New languages belong in standard Android locale-qualified resource directories. Do not describe a language as fully supported until its main flows, dialogs, accessibility labels, and settings have actually been translated and verified; the language picker states that untranslated text appears in Chinese.

### Hard-coded text ratchet

`HardcodedTextRatchetTest` counts string literals containing Chinese characters in each Kotlin file under `feature/`, `ui/` and `MainActivity.kt`, and compares them with [a baseline](../app/src/test/resources/i18n/hardcoded-han-baseline.txt). A file may not gain literals: new UI text goes into resources. When a migration removes literals the test also fails until the baseline is regenerated, which locks in progress:

```sh
MOREAD_UPDATE_I18N_BASELINE=1 ./gradlew :app:testDebugUnitTest --tests '*HardcodedTextRatchetTest*'
```

Regenerate it the same way for literals that are genuinely not UI text (for example Chinese patterns used to parse book content). `ai/` and `core/` are out of scope because their Chinese text is mostly model prompts, tool results and parsing rules.

## Verification

Run:

```sh
./gradlew :app:testDebugUnitTest
```

[LocalizationResourcesTest](../app/src/test/java/com/mozhi/reader/core/LocalizationResourcesTest.kt) checks English selection, default-resource fallback, indexed arguments, percentages, and plural behavior with Android resources under Robolectric. Extend it for new formatting patterns. [LocalizationResourceParityTest](../app/src/test/java/com/mozhi/reader/core/i18n/LocalizationResourceParityTest.kt) checks that every translation only covers keys that exist in the default resources, keeps the same placeholders, does not translate `translatable="false"` entries, and uses indexed placeholders. `AppLocalesTest` covers language-tag mapping, the per-app locale round trip and `UiText` resolution. Resource compilation also catches malformed XML and invalid format strings.

Robolectric runs in `en-US` by default, so UI tests that assert Chinese labels must pin the locale with a `zh-rCN` qualifier (for example `@Config(qualifiers = "zh-rCN-w411dp-h891dp-mdpi")`); a method-level `qualifiers` value replaces the class value, so repeat the prefix there.

For each migrated flow, check Chinese and English on a narrow phone and a short/landscape window, with a larger font scale. Confirm that menus remain reachable, touch targets stay at least 44dp, counts and percentages are correct, and translated text is not clipped. JVM tests complement—but do not replace—testing on a device.

Book-menu layout and pointer regressions live in `BookLongPressOverlayTest` and `ReaderPageTouchTest`. Pull-to-bookmark must remain disabled in continuous vertical scrolling mode regardless of locale.

## Documentation

Keep [README.md](../README.md) and [README.zh-CN.md](../README.zh-CN.md) aligned when changing features, setup steps, privacy statements, or limitations. The English README must continue to disclose partial app localization until a complete English UI is available. Update the public [code map](CODE_MAP.md) when moving resource-related entry points.
