/* Offline worker: all dependency source comes from signed APK assets supplied over stdin. */
"use strict";
const fs=require("node:fs"),path=require("node:path").posix;
const builtins=new Set(["path","url","util","module"]);
function engine(bundle){
  if(bundle.version!==1 || !bundle.modules || Object.keys(bundle.modules).length>20000)throw Error("invalid library bundle");
  const files=bundle.modules,cache=Object.create(null);
  const own=(key)=>Object.prototype.hasOwnProperty.call(files,key);
  function file(candidate,depth=0){
    candidate=path.normalize(candidate);if(candidate.startsWith("../")||candidate.startsWith("/")||depth>8)throw Error("invalid module path");
    if(own(candidate))return candidate;
    for(const suffix of [".js",".json"])if(own(candidate+suffix))return candidate+suffix;
    if(own(candidate+"/package.json")){const metadata=JSON.parse(files[candidate+"/package.json"]);if(metadata.main){try{return file(candidate+"/"+metadata.main,depth+1)}catch(_) {}}}
    if(own(candidate+"/index.js"))return candidate+"/index.js";
    throw Error("offline module missing: "+candidate);
  }
  function load(request,from=""){
    if(builtins.has(request))return require("node:"+request);
    const name=file(request.startsWith(".")?path.dirname(from)+"/"+request:request);
    if(cache[name])return cache[name].exports;
    const module={exports:{}};cache[name]=module;
    if(name.endsWith(".json")){module.exports=JSON.parse(files[name]);return module.exports;}
    const source=files[name].replace(/^#![^\n]*\n/,"");
    new Function("require","module","exports","__filename","__dirname",source)((key)=>load(key,name),module,module.exports,"/offline/"+name,"/offline/"+path.dirname(name));
    return module.exports;
  }
  return load;
}
const mapKeywords=["properties","patternProperties","$defs","definitions","dependentSchemas","dependencies"];
const listKeywords=["allOf","anyOf","oneOf","prefixItems"];
const singleKeywords=["items","additionalItems","additionalProperties","contains","not","if","then","else","propertyNames","unevaluatedProperties","unevaluatedItems","contentSchema"];
const vocabulary=new Set(["https://json-schema.org/draft/2020-12/vocab/core","https://json-schema.org/draft/2020-12/vocab/applicator","https://json-schema.org/draft/2020-12/vocab/validation","https://json-schema.org/draft/2020-12/vocab/meta-data","https://json-schema.org/draft/2020-12/vocab/unevaluated","https://json-schema.org/draft/2020-12/vocab/content","https://json-schema.org/draft/2020-12/vocab/format-annotation","https://json-schema.org/draft/2020-12/vocab/format-assertion","https://json-schema.org/draft/2019-09/vocab/core","https://json-schema.org/draft/2019-09/vocab/applicator","https://json-schema.org/draft/2019-09/vocab/validation","https://json-schema.org/draft/2019-09/vocab/meta-data","https://json-schema.org/draft/2019-09/vocab/content","https://json-schema.org/draft/2019-09/vocab/format"]);
function walk(schema,visit,depth=0,budget={nodes:0}){
  if(depth>64 || ++budget.nodes>50000)throw Error("schema budget exceeded");
  if(typeof schema==="boolean")return;
  if(!schema || typeof schema!=="object" || Array.isArray(schema))throw Error("invalid schema node");
  visit(schema);
  for(const key of mapKeywords)if(schema[key] && typeof schema[key]==="object" && !Array.isArray(schema[key]))for(const value of Object.values(schema[key]))if(typeof value==="boolean" || value && typeof value==="object" && !Array.isArray(value))walk(value,visit,depth+1,budget);
  for(const key of listKeywords)if(Array.isArray(schema[key]))for(const value of schema[key])walk(value,visit,depth+1,budget);
  for(const key of singleKeywords)if(schema[key]!==undefined){if(key==="items" && Array.isArray(schema[key]))schema[key].forEach(value=>walk(value,visit,depth+1,budget));else walk(schema[key],visit,depth+1,budget);}
}
function dialect(schema){
  const raw=schema.$schema;
  if(raw===undefined)return "2020";
  if(typeof raw!=="string")throw Error("invalid schema dialect");
  if(/^https?:\/\/json-schema.org\/draft-04\/schema#?$/.test(raw))return "04";
  if(/^https?:\/\/json-schema.org\/draft-06\/schema#?$/.test(raw))return "06";
  if(/^https?:\/\/json-schema.org\/draft-07\/schema#?$/.test(raw))return "07";
  if(/^https?:\/\/json-schema.org\/draft\/2019-09\/schema#?$/.test(raw))return "2019";
  if(/^https?:\/\/json-schema.org\/draft\/2020-12\/schema#?$/.test(raw))return "2020";
  throw Error("unsupported schema dialect");
}
function validateRequest(request,bundle){
  if(!request || request.version!==1 || !Array.isArray(request.resources)||request.resources.length>32)throw Error("invalid validation request");
  const schemas=[request.schema,...request.resources];let ruleBytes=0;
  for(const schema of schemas){ruleBytes+=Buffer.byteLength(JSON.stringify(schema));walk(schema,node=>{
    if(node.$async!==undefined || node.$data!==undefined)throw Error("asynchronous or data-evaluated schemas are unavailable");
    if(node.$schema!==undefined)dialect(node);
    if(node.$vocabulary!==undefined){if(!node.$vocabulary || typeof node.$vocabulary!=="object" || Array.isArray(node.$vocabulary))throw Error("invalid vocabulary");for(const [key,required]of Object.entries(node.$vocabulary)){if(typeof required!=="boolean" || required && !vocabulary.has(key))throw Error("unknown required vocabulary");}}
  });}
  if(ruleBytes>256*1024 || Buffer.byteLength(JSON.stringify(request.value))>65536)throw Error("validation data exceeds budget");
  const load=engine(bundle),kind=dialect(request.schema);
  const Constructor=kind==="04"?load("ajv-draft-04"):kind==="2020"?load("ajv/dist/2020.js"):kind==="2019"?load("ajv/dist/2019.js"):load("ajv");
  const Ajv=Constructor.default || Constructor;
  const ajv=new Ajv({strict:false,allErrors:false,ownProperties:true,validateFormats:true,validateSchema:true,coerceTypes:false,useDefaults:false,removeAdditional:false,$data:false,logger:false,unicodeRegExp:true});
  // JSON decimal divisibility is exact; a floating tolerance could approve a near-multiple.
  const decimal=value=>{const [mantissa,power="0"]=String(value).toLowerCase().split("e");const parts=mantissa.split(".");return [BigInt(parts.join("")),Number(power)-(parts[1]||"").length];};
  ajv.removeKeyword("multipleOf");
  ajv.addKeyword({keyword:"multipleOf",type:"number",schemaType:"number",errors:false,validate:(divisor,value)=>{
    if(!(divisor>0) || !Number.isFinite(value))return false;
    const [a,ae]=decimal(value),[b,be]=decimal(divisor),base=Math.min(ae,be);
    return (a*10n**BigInt(ae-base))%(b*10n**BigInt(be-base))===0n;
  }});
  if(kind==="06")ajv.addMetaSchema(load("ajv/dist/refs/json-schema-draft-06.json"));
  if(kind==="2019")ajv.addMetaSchema(load("ajv/dist/refs/json-schema-draft-07.json"));
  if(["04","06","07"].includes(kind)){
    const meta=load(kind==="04"?"ajv-draft-04/dist/refs/json-schema-draft-04.json":`ajv/dist/refs/json-schema-draft-${kind}.json`);
    ajv.addMetaSchema({...meta,[kind==="04"?"id":"$id"]:`https://json-schema.org/draft-${kind}/schema#`});
  }
  const formats=load("ajv-formats");(formats.default||formats)(ajv,{mode:"full"});
  const international=load("ajv-formats-draft2019");(international.default||international)(ajv);
  // RFC5321 permits a single-label domain; use the pinned SMTP grammar rather than the narrower dotted-domain regex.
  const smtp=load("smtp-address-parser");
  ajv.addFormat("email",{type:"string",validate:value=>{if(/[^\x00-\x7f]/.test(value))return false;try{smtp.parse(value);return true;}catch(_){return false;}}});
  for(const schema of schemas)walk(schema,node=>{
    if(node.$schema && dialect(node)!==kind)throw Error("mixed embedded dialects are unavailable");
    if(node.format!==undefined && (typeof node.format!=="string" || !Object.prototype.hasOwnProperty.call(ajv.formats,node.format)))throw Error("unsupported format");
  });
  for(const resource of request.resources){if(!resource.$id || typeof resource.$id!=="string")throw Error("offline resources need an absolute identifier");const id=new URL(resource.$id);if(!id.protocol)throw Error("invalid resource identifier");if(resource.$schema && dialect(resource)!==kind)throw Error("mixed resource dialects are unavailable");ajv.addSchema(resource);}
  const check=ajv.compile(request.schema);const valid=check(request.value);
  return {valid:valid===true,errors:valid?[]:(check.errors||[]).slice(0,8).map(row=>({path:row.instancePath||"",keyword:row.keyword,message:row.message||"invalid"}))};
}
if(require.main===module || process.env.CODEX_SCHEMA_WORKER==="1"){
  try{const bytes=fs.readFileSync(0);if(bytes.length>10*1024*1024)throw Error("worker input exceeds budget");const input=JSON.parse(bytes.toString("utf8"));process.stdout.write(JSON.stringify(validateRequest(input.request,input.bundle))+"\n");}
  catch(error){process.stdout.write(JSON.stringify({valid:false,unsupported:true,message:String(error.message||error).slice(0,512)})+"\n");process.exitCode=78;}
}
module.exports={validateRequest,engine};
