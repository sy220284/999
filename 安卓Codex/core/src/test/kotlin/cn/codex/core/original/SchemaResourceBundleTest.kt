package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class SchemaResourceBundleTest {
    @Test fun explicitAbsoluteDocumentsAreRequiredAndDuplicatesCannotReplaceRules(){
        val schema="""{"@id":"https://s.invalid/r","type":"object"}""".replace('@','$')
        assertEquals(1,SchemaResourceBundle.parse(schema).size)
        assertFailsWith<IllegalArgumentException>{SchemaResourceBundle.parse("[$schema,$schema]")}
        assertFailsWith<IllegalArgumentException>{SchemaResourceBundle.parse("{\"type\":\"object\"}")}
    }
    @Test fun resourceImportHonorsTheWholeDocumentBudget(){
        assertFailsWith<IllegalArgumentException>{SchemaResourceBundle.parse(" ".repeat(256*1024+1))}
        assertFailsWith<IllegalArgumentException>{SchemaResourceBundle.parse("[]")}
    }
}
