package cn.codex.core.original
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
class JsonSchemaCompletionTest {
    private fun validator(text:String)=JsonSchemaFormValidator(Json.parseToJsonElement(text).jsonObject)
    private fun value(text:String)=Json.parseToJsonElement(text)
    @Test fun adjacentApplicatorsContributeEvaluatedPropertiesButUnclesDoNot() {
        val valid=validator("""{"allOf":[{"properties":{"a":{"type":"string"}}}],"unevaluatedProperties":false}""")
        valid.validate(value("""{"a":"ok"}"""))
        assertFailsWith<IllegalArgumentException>{valid.validate(value("""{"a":"ok","b":1}"""))}
        val uncle=validator("""{"allOf":[{"properties":{"a":true}},{"unevaluatedProperties":false}]}""")
        assertFailsWith<IllegalArgumentException>{uncle.validate(value("""{"a":1}"""))}
    }
    @Test fun failedChoiceAnnotationsCannotAuthorizeAdditionalFields() {
        val v=validator("""{"anyOf":[{"properties":{"bad":true},"required":["missing"]},{"properties":{"good":true}}],"unevaluatedProperties":false}""")
        assertFailsWith<IllegalArgumentException>{v.validate(value("""{"bad":1,"good":2}"""))}
        v.validate(value("""{"good":2}"""))
    }
    @Test fun containsMarksOnlyActuallyMatchingItems() {
        val v=validator("""{"contains":{"type":"integer"},"unevaluatedItems":false}""")
        v.validate(value("[1,2]"))
        assertFailsWith<IllegalArgumentException>{v.validate(value("[1,\"bad\"]"))}
    }
    @Test fun recursiveLocalTreesValidateFiniteInstancesAndRejectMalformedLeaves() {
        val v=validator("""{"type":"object","properties":{"value":{"type":"integer"},"child":{"${'$'}ref":"#"}},"required":["value"],"additionalProperties":false}""")
        assertTrue(v.supported());v.validate(value("""{"value":1,"child":{"value":2}}"""))
        assertFailsWith<IllegalArgumentException>{v.validate(value("""{"value":1,"child":{"value":"bad"}}"""))}
    }
    @Test fun malformedLimitsAndUnsafePatternsAreNotDeclaredSupported() {
        assertFalse(validator("""{"type":"object","minProperties":"2"}""").supported())
        assertFalse(validator("""{"type":"string","pattern":"(a)\\1"}""").supported())
        assertFalse(validator("""{"type":"object","${'$'}ref":"https://example.invalid/schema"}""").supported())
    }
    @Test fun imagePickerReturnsOnlyAnAllowedOptionIdentity() {
        val form=ElicitationForm(value("""{"type":"object","properties":{"template":{"type":"openai/imagePicker","items":[{"id":"a","title":"方案甲","image":"data:image/svg+xml;base64,PHN2Zy8+"}]}},"required":["template"]}""").jsonObject)
        assertTrue(form.supported);assertEquals(JsonPrimitive("a"),form.content(mapOf("template" to "a"))["template"])
        assertFailsWith<IllegalArgumentException>{form.content(mapOf("template" to "other"))}
    }
    @Test fun titledMultiSelectionPreservesTheArrayContract() {
        val form=ElicitationForm(value("""{"type":"object","properties":{"items":{"type":"array","minItems":1,"items":{"anyOf":[{"const":"a","title":"甲"},{"const":"b","title":"乙"}]}}},"required":["items"]}""").jsonObject)
        assertTrue(form.supported);assertEquals(2,form.content(mapOf("items" to "[\"a\",\"b\"]")).getValue("items").jsonArray.size)
        assertFailsWith<IllegalArgumentException>{form.content(mapOf("items" to "[]"))}
    }
    @Test fun annotationObjectsAreNotReinterpretedAsWidgetSchemas() {
        val form=ElicitationForm(value("""{"type":"object","properties":{"value":{"type":"object","const":{"type":"openai/imagePicker","items":[]}}}}""").jsonObject)
        assertTrue(form.supported)
        form.content(mapOf("value" to """{"type":"openai/imagePicker","items":[]}"""))
    }
    @Test fun resourceIdsAreNotSilentlyTreatedAsTheRootDocument() {
        assertFalse(validator("""{"${'$'}id":"https://example.invalid/other","type":"object"}""").supported())
    }
    @Test fun genericArrayChoicesDoNotPretendToBePrimitiveMultiSelectors() {
        val form=ElicitationForm(value("""{"type":"object","properties":{"value":{"type":"array","items":{"anyOf":[{"type":"object"},{"type":"string"}]}}}}""").jsonObject)
        assertTrue(form.supported);assertNull(form.fields.single().multiOptions)
    }

}
