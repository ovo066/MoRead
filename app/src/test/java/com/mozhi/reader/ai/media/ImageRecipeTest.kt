package com.mozhi.reader.ai.media

import org.junit.Assert.*
import org.junit.Test

class ImageRecipeTest {
    private val first = LookSpec("look-1", "shen", "沈砚", 0, "black hair; grey robe", "black_hair, grey_robe", referenceIds = listOf("shen-ref"))
    private val later = first.copy(id = "look-2", sinceChapter = 80, natural = "white hair; scar")
    private val second = LookSpec("look-su", "su", "苏晚", natural = "red scarf", referenceIds = listOf("su-ref"))

    @Test fun lookVersionsNeverLeakFutureAppearanceAndIdentitySurvivesRenaming() {
        assertEquals(first, visibleLook(listOf(later, first), "shen", 79))
        assertEquals(later, visibleLook(listOf(later, first), "shen", 80))
        assertNull(visibleLook(listOf(later), "shen", 0))
        assertEquals("更名", visibleLook(listOf(first.copy(name = "更名")), "shen", 0)?.name)
    }
    @Test fun repeatedScenesKeepExactAppearanceAndStyleInBothDialects() {
        val style = StyleSpec(natural = "fixed watercolor", tags = "artist:a, watercolor", negative = "watermark")
        repeat(5) { i ->
            val recipe = ImageRecipe(style = style, cast = listOf(first, second), shot = ShotSpec(action = "scene $i"))
            val natural = RecipeAssembler.assemble(recipe, ImageCapabilities())
            assertTrue(natural.prompt.contains(first.natural))
            assertTrue(natural.prompt.contains(second.natural))
            assertTrue(natural.prompt.contains(style.natural))
            val tags = RecipeAssembler.assemble(recipe, ImageCapabilities(tags = true, perCharacterPrompt = true))
            assertEquals(first.tags, tags.characterPrompts.first())
            assertFalse(tags.base.contains(first.tags))
            assertTrue(tags.base.contains(style.tags))
            assertEquals("watermark", tags.negative)
        }
    }
    @Test fun novelAiReferencePlanningDoesNotBlendPeopleOrCombinePreciseAndVibe() {
        val caps = ImageCapabilities(maxReferences = 3, maxCharacterReferences = 1, vibe = true,
            exclusiveCharacterAndStyle = true, characterReferenceCost = 5)
        val plan = planReferences(caps, listOf(first, second), StyleSpec(referenceIds = listOf("style")))
        assertEquals(listOf("shen-ref"), plan.references.map { it.assetId })
        assertEquals(5, plan.extraCostPerImage)
        assertTrue(ReferenceNotice.PRIMARY_CHARACTER_ONLY in plan.notices)
        assertTrue(ReferenceNotice.VIBE_WITHOUT_CHARACTER in plan.notices)
    }
    @Test fun overflowPrioritizesMainCharacterThenStylesAndRetainsTextLooks() {
        val plan = planReferences(ImageCapabilities(maxReferences = 2, maxCharacterReferences = 2),
            listOf(first, second), StyleSpec(referenceIds = listOf("style")))
        assertEquals(listOf("shen-ref", "style"), plan.references.map { it.assetId })
        assertTrue(ReferenceNotice.REFERENCE_LIMIT in plan.notices)
        val recipe = ImageRecipe(cast = listOf(first, second), references = plan.references)
        assertTrue(RecipeAssembler.assemble(recipe, ImageCapabilities()).prompt.contains("red scarf"))
    }
    @Test fun vibeStrengthsAreNormalizedAndNoReferencesMeansNoCost() {
        val style = StyleSpec(referenceIds = listOf("a", "b", "c"), referenceStrength = .8f)
        val plan = planReferences(ImageCapabilities(maxReferences = 3, vibe = true), emptyList(), style)
        assertEquals(1f, plan.references.sumOf { it.strength.toDouble() }.toFloat(), .0001f)
        assertEquals(0, plan.extraCostPerImage)
        assertTrue(planReferences(ImageCapabilities(), listOf(first), style).references.isEmpty())
        assertTrue(planReferences(ImageCapabilities(maxReferences = 4), listOf(first), style, enabled = false).references.isEmpty())
    }
    @Test fun shotParserFiltersUnknownIdsAndNeverReadsAppearanceFields() {
        val shot = ShotParser.parse("""```json
            {"cast":["shen","spoiler","shen"],"action":"opens window","appearance":"white hair"}
        ```""".trimIndent(), setOf("shen"))
        assertEquals(listOf("shen"), shot.cast)
        assertEquals("opens window", shot.text())
    }
    @Test fun recipeRoundTripRetainsSnapshotsAndRerollOnlyChangesSeed() {
        val recipe = ImageRecipe(cast = listOf(first), seed = 123, backend = "model", shot = ShotSpec(action = "reading"))
        val loaded = ImageRecipeCodec.decode(recipe.encode())!!
        val reroll = loaded.copy(seed = 456)
        assertEquals(recipe, loaded)
        assertEquals(recipe, reroll.copy(seed = recipe.seed))
        assertFalse(recipe.encode().contains("Bearer"))
    }
}
