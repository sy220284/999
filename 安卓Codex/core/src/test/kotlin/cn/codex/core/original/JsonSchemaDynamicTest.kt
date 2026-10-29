package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class JsonSchemaDynamicTest {
    private fun obj(text:String)=Json.parseToJsonElement(text.replace('@','$')).jsonObject
    @Test fun dynamicReferenceUsesOutermostMatchingResourceAndChecksChildren(){
        val tree=obj("""{"@id":"https://test.invalid/tree","@dynamicAnchor":"node","type":"object","properties":{"data":true,"children":{"type":"array","items":{"@dynamicRef":"#node"}}}}""")
        val strict=obj("""{"@id":"https://test.invalid/strict","@dynamicAnchor":"node","@ref":"https://test.invalid/tree","unevaluatedProperties":false}""")
        val validator=JsonSchemaFormValidator(strict,listOf(tree));assertTrue(validator.supported())
        validator.validate(obj("""{"data":1,"children":[{"data":2}]}"""))
        assertFailsWith<IllegalArgumentException>{validator.validate(obj("""{"children":[{"typo":2}]}"""))}
    }
    @Test fun plainAnchorWithDynamicRefDoesNotActivateOuterDynamicOverride(){
        val target=obj("""{"@id":"https://test.invalid/base","@anchor":"node","type":"object","properties":{"allowed":true},"additionalProperties":false}""")
        val root=obj("""{"@id":"https://test.invalid/root","@dynamicAnchor":"node","type":"object","properties":{"child":{"@dynamicRef":"https://test.invalid/base#node"}}}""")
        val validator=JsonSchemaFormValidator(root,listOf(target));assertTrue(validator.supported());validator.validate(obj("""{"child":{"allowed":1}}"""))
        assertFailsWith<IllegalArgumentException>{validator.validate(obj("""{"child":{"child":{}}}"""))}
    }
    @Test fun conflictingExternalResourcesAndUnknownRequiredVocabularyAreRejected(){
        val root=obj("""{"type":"object","@ref":"https://test.invalid/r"}""");val document=obj("""{"@id":"https://test.invalid/r","type":"object"}""")
        assertFalse(JsonSchemaFormValidator(root,listOf(document,document)).supported())
        assertFalse(JsonSchemaFormValidator(obj("""{"type":"object","@vocabulary":{"https://unknown.invalid/rules":true}}""")).supported())
        assertFalse(JsonSchemaFormValidator(obj("""{"type":"object","@dynamicAnchor":"x","@anchor":"x"}""")).supported())
        assertFalse(JsonSchemaFormValidator(obj("""{"@schema":"https://unknown.invalid/dialect","type":"object"}""")).supported())
    }
}
