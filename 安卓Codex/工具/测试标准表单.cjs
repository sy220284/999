"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs");
const {validateRequest}=require("../app/src/main/assets/schema-validator/standard-schema.cjs");
const bundle=JSON.parse(fs.readFileSync(process.argv[2],"utf8"));let count=0;
function test(name,fn){try{fn();count++;}catch(e){console.error("FAIL "+name+": "+e.message);throw e;}}
function check(schema,value,expected,resources=[]){const before=JSON.stringify(value);const result=validateRequest({version:1,schema,value,resources},bundle);assert.equal(result.valid,expected);assert.equal(JSON.stringify(value),before);}
const object=(property)=>({type:"object",properties:{value:property},required:["value"],additionalProperties:false});
for(const draft of ["04","06","07"])test("draft "+draft+" legacy tuple and false branch",()=>{
 const schema={$schema:`http://json-schema.org/draft-${draft}/schema#`,type:"array",items:[{type:"string"},{type:"integer"}],additionalItems:false};check(schema,["x",1],true);check(schema,["x","1"],false);
});
test("2019 dependent and unevaluated properties",()=>{const schema={$schema:"https://json-schema.org/draft/2019-09/schema",type:"object",properties:{a:{type:"integer"}},dependentRequired:{a:["b"]},patternProperties:{"^b$":{type:"string"}},unevaluatedProperties:false};check(schema,{a:1,b:"x"},true);check(schema,{a:1},false);check(schema,{a:1,b:"x",c:0},false);});
test("2020 prefixItems and contains",()=>{const schema={type:"array",prefixItems:[{type:"string"}],items:{type:"integer"},contains:{const:2},minContains:1,maxContains:1};check(schema,["x",1,2],true);check(schema,["x",2,2],false);});
test("dynamic scope reaches strict children",()=>{const tree={$id:"https://test.invalid/tree",$dynamicAnchor:"node",type:"object",properties:{data:true,children:{type:"array",items:{$dynamicRef:"#node"}}}};const strict={$id:"https://test.invalid/strict",$dynamicAnchor:"node",$ref:"https://test.invalid/tree",unevaluatedProperties:false};check(strict,{data:1,children:[{data:2}]},true,[tree]);check(strict,{children:[{typo:2}]},false,[tree]);});
test("ECMAScript lookbehind instead of RE2 subset",()=>{check(object({type:"string",pattern:"(?<=a)b"}),{value:"ab"},true);check(object({type:"string",pattern:"(?<=a)b"}),{value:"ac"},false);});
test("full email parser permits local domains",()=>{check(object({type:"string",format:"email"}),{value:"a@localhost"},true);check(object({type:"string",format:"email"}),{value:"a@@localhost"},false);});
test("internationalized hostname",()=>{check(object({type:"string",format:"idn-hostname"}),{value:"例子.测试"},true);});
test("internationalized email",()=>{check(object({type:"string",format:"idn-email"}),{value:"用户@例子.广告"},true);});
test("IRI and URI template",()=>{check(object({type:"string",format:"iri"}),{value:"https://例子.测试/路径"},true);check(object({type:"string",format:"uri-template"}),{value:"https://example.invalid/{id}{?q}"},true);});
test("duration supports combined date and time",()=>{check(object({type:"string",format:"duration"}),{value:"P1Y2M3DT4H5M6S"},true);check(object({type:"string",format:"duration"}),{value:"not a duration"},false);});
test("relative JSON pointer",()=>{check(object({type:"string",format:"relative-json-pointer"}),{value:"1/foo"},true);check(object({type:"string",format:"relative-json-pointer"}),{value:"01/foo"},false);});
test("IPv4 mapped IPv6",()=>{check(object({type:"string",format:"ipv6"}),{value:"::ffff:192.0.2.1"},true);});
test("no input coercion or inserted defaults",()=>{check(object({type:"integer",default:7}),{value:"7"},false);check(object({type:"integer",default:7}),{},false);});
test("missing remote resources are never fetched",()=>{assert.throws(()=>check({$ref:"https://unavailable.invalid/schema"},{},true),/resolve|reference|schema/i);});
test("unknown required vocabularies fail closed",()=>{assert.throws(()=>check({$vocabulary:{"https://unavailable.invalid/vocab":true},type:"object"},{},true),/vocabulary/);});
test("unsupported dialects fail closed",()=>{assert.throws(()=>check({$schema:"https://unavailable.invalid/dialect",type:"object"},{},true),/dialect/);});
test("decimal multipleOf",()=>{check(object({type:"number",multipleOf:0.1}),{value:0.3},true);check(object({type:"number",multipleOf:0.1}),{value:0.31},false);});
test("near decimal multiples are rejected exactly",()=>check(object({type:"number",multipleOf:0.1}),{value:0.30000000000001},false));
for(const draft of ["04","06","07"])test("HTTPS meta-schema alias "+draft,()=>check({$schema:`https://json-schema.org/draft-${draft}/schema#`,type:"object"},{},true));
test("unknown formats cannot silently approve",()=>assert.throws(()=>check(object({type:"string",format:"unsupported-custom-format"}),{value:"x"},true),/unsupported format/));
test("mixed embedded dialects fail closed",()=>assert.throws(()=>check({type:"object",properties:{a:{$schema:"http://json-schema.org/draft-07/schema#",type:"string"}}},{a:"x"},true),/mixed embedded/));
console.log("PASS "+count+" pure JavaScript standard-schema cases; no Android build or service execution");
