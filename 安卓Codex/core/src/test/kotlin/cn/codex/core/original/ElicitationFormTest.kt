package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ElicitationFormTest {
    private fun schema(raw: String) = ElicitationForm(Json.parseToJsonElement(raw).jsonObject)
    @Test fun typedAnswersPreserveNumbersBooleansAndOmitUnansweredOptionalFields() {
        val form = schema("""{"type":"object","required":["count","confirm"],"properties":{"count":{"type":"integer","minimum":1},"confirm":{"type":"boolean"},"note":{"type":"string"}}}""")
        val result = form.content(mapOf("count" to "3", "confirm" to "false"))
        assertFalse(result.getValue("count").jsonPrimitive.isString)
        assertEquals(false, result.getValue("confirm").jsonPrimitive.boolean)
        assertFalse(result.containsKey("note"))
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("count" to "1.5", "confirm" to "true")) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("count" to "0", "confirm" to "true")) }
    }
    @Test fun enumPatternAndRequiredFieldsAreValidated() {
        val form = schema("""{"type":"object","required":["name"],"properties":{"name":{"type":"string","pattern":"^[a-z]+$","minLength":2,"enum":["ok","yes"]}}}""")
        assertEquals("ok", form.content(mapOf("name" to "ok")).getValue("name").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { form.content(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("name" to "bad")) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("name" to "OK")) }
    }
    @Test fun nestedAndConditionalFormsAreAcceptedButExternalReferencesAreNot() {
        assertTrue(schema("""{"type":"object","properties":{"nested":{"type":"object"}}}""").supported)
        assertTrue(schema("""{"type":"object","oneOf":[{},{}],"properties":{}}""").supported)
        assertFalse(schema("""{"type":"object","${'$'}ref":"https://example.com/schema"}""").supported)
    }
    @Test fun nestedArraysAndConditionalDependenciesValidateActualValues() {
        val form = schema("""{"type":"object","required":["rows"],"properties":{"rows":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"object","required":["count"],"additionalProperties":false,"properties":{"count":{"type":"integer","minimum":1}}}}}}""")
        assertTrue(form.supported)
        assertEquals(2, form.content(mapOf("rows" to "[{\"count\":2}]")).getValue("rows").jsonArray.single().jsonObject.getValue("count").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("rows" to "[]")) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("rows" to "[{\"count\":0}]")) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("rows" to "[{\"count\":1},{\"count\":1.0}]")) }
    }
    @Test fun localReferencesAndChoiceTitlesAreResolvedWithoutNetwork() {
        val form = schema("""{"type":"object","${'$'}defs":{"positive":{"type":"integer","minimum":1}},"properties":{"count":{"${'$'}ref":"#/${'$'}defs/positive"},"choice":{"oneOf":[{"const":"a","title":"甲"},{"const":"b","title":"乙"}]}}}""")
        assertTrue(form.supported)
        assertEquals("甲", form.fields.last().label(0))
        assertEquals("a", form.content(mapOf("count" to "1", "choice" to "a")).getValue("choice").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("count" to "0")) }
    }
    @Test fun rootConditionalFormCanSupplyBranchFieldsAsStructuredJson() {
        val form = schema("""{"type":"object","properties":{"kind":{"type":"string"}},"if":{"properties":{"kind":{"const":"work"}}},"then":{"required":["hours"],"properties":{"hours":{"type":"number","multipleOf":0.5}}}}""")
        assertTrue(form.rawInput)
        form.content(mapOf("__form_json" to "{\"kind\":\"work\",\"hours\":1.5}"))
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("__form_json" to "{\"kind\":\"work\"}")) }
        assertFailsWith<IllegalArgumentException> { form.content(mapOf("__form_json" to "{\"kind\":\"work\",\"hours\":1.2}")) }
    }
    @Test fun unknownValidationVocabularyIsNotSilentlyAccepted() {
        assertFalse(schema("""{"type":"object","unevaluatedProperties":false}""").supported)
        assertFalse(schema("""{"type":"object","${'$'}ref":"#"}""").supported)
    }

    @Test fun unsupportedFormatsInvalidRegexAndMalformedTypesAreNotMarkedSupported() {
        assertFalse(schema("""{"type":"string","format":"hostname"}""").supported)
        assertFalse(schema("""{"type":"object","patternProperties":{"[":{"type":"string"}}}""").supported)
        assertFalse(schema("""{"type":["string","future"]}""").supported)
        assertFalse(schema("""{"type":"object","required":"name"}""").supported)
        assertFalse(schema("""{"type":"object","dependentRequired":{"a":"b"}}""").supported)
        assertTrue(schema("""{"type":"string","format":"email"}""").supported)
        assertTrue(schema("""{"type":"array","items":{"type":"integer"}}""").supported)
    }

}
