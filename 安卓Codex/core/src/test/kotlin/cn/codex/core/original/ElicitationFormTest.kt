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
    @Test fun nestedAndConditionalFormsAreExplicitlyUnsupported() {
        assertFalse(schema("""{"type":"object","properties":{"nested":{"type":"object"}}}""").supported)
        assertFalse(schema("""{"type":"object","oneOf":[{},{}],"properties":{}}""").supported)
    }
}
