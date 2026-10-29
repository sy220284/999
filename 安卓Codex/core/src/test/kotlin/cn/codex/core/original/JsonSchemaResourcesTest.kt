package cn.codex.core.original
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
class JsonSchemaResourcesTest {
    private fun v(s:String)=JsonSchemaFormValidator(Json.parseToJsonElement(s).jsonObject)
    @Test fun embeddedResourcesResolveRelativeReferencesFromTheirOwnBase() {
        val validator=v("""{"${'$'}id":"https://example.invalid/root.json","type":"object","${'$'}defs":{"integer":{"${'$'}id":"types/integer.json","type":"integer"},"child":{"${'$'}id":"types/child.json","type":"object","properties":{"value":{"${'$'}ref":"integer.json"}}}},"properties":{"child":{"${'$'}ref":"types/child.json"}}}""")
        assertTrue(validator.supported())
        validator.validate(Json.parseToJsonElement("""{"child":{"value":2}}"""))
        assertFailsWith<IllegalArgumentException>{validator.validate(Json.parseToJsonElement("""{"child":{"value":"wrong"}}"""))}
    }
    @Test fun fragmentPointersUseTheReferencedResourceRoot() {
        val validator=v("""{"${'$'}id":"https://example.invalid/root","${'$'}defs":{"child":{"${'$'}id":"child","${'$'}defs":{"number":{"type":"number"}}}},"${'$'}ref":"child#/${'$'}defs/number"}""")
        assertTrue(validator.supported());validator.validate(JsonPrimitive(2))
        assertFailsWith<IllegalArgumentException>{validator.validate(JsonPrimitive("bad"))}
    }
    @Test fun identicalAnchorNamesInDifferentResourcesDoNotConflict() {
        val validator=v("""{"${'$'}id":"https://example.invalid/root","${'$'}defs":{"a":{"${'$'}id":"a","${'$'}anchor":"value","type":"integer"},"b":{"${'$'}id":"b","${'$'}anchor":"value","type":"string"}},"${'$'}ref":"b#value"}""")
        assertTrue(validator.supported());validator.validate(JsonPrimitive("ok"))
        assertFailsWith<IllegalArgumentException>{validator.validate(JsonPrimitive(1))}
    }
    @Test fun missingResourcesAndDuplicateIdsAreRejectedWithoutFetching() {
        assertFalse(v("""{"${'$'}ref":"https://example.invalid/missing"}""").supported())
        assertFalse(v("""{"${'$'}defs":{"a":{"${'$'}id":"same"},"b":{"${'$'}id":"same"}}}""").supported())
        assertFalse(v("""{"${'$'}id":"https://example.invalid/root#invalid"}""").supported())
    }
    @Test fun instanceAnnotationsCannotRegisterSchemaResources() {
        val validator=v("""{"examples":[{"${'$'}id":"https://example.invalid/pretend","type":"string"}],"${'$'}ref":"https://example.invalid/pretend"}""")
        assertFalse(validator.supported())
    }
    @Test fun emptyReferenceStillAppliesTheCurrentResourceRules() {
        val validator=v("""{"type":"object","required":["value"],"properties":{"value":{"type":"integer"},"child":{"${'$'}ref":""}}}""")
        assertTrue(validator.supported())
        validator.validate(Json.parseToJsonElement("""{"value":1,"child":{"value":2}}"""))
        assertFailsWith<IllegalArgumentException>{validator.validate(Json.parseToJsonElement("""{"value":1,"child":{}}"""))}
    }

}
