#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Build isolated plugin images and verify real-engine analysis; stock containers are untouched."""
import argparse, datetime, hashlib, json, os, shutil, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
TARGETS={
 "es7173":("elasticsearch-7","7.17.3","docker.elastic.co/elasticsearch/elasticsearch:7.17.3"),
 "es7":("elasticsearch-7","7.17.29","docker.elastic.co/elasticsearch/elasticsearch:7.17.29"),
 "es80":("elasticsearch-8","8.0.0","docker.elastic.co/elasticsearch/elasticsearch:8.0.0"),
 "es87":("elasticsearch-stable-8","8.7.0","docker.elastic.co/elasticsearch/elasticsearch:8.7.0"),
 "es8":("elasticsearch-stable-8","8.7.0","docker.elastic.co/elasticsearch/elasticsearch:8.19.21"),
 "es90":("elasticsearch-stable-9","9.0.0","docker.elastic.co/elasticsearch/elasticsearch:9.0.0"),
 "es9":("elasticsearch-stable-9","9.0.0","docker.elastic.co/elasticsearch/elasticsearch:9.5.3"),
 "os2":("opensearch-2","2.19.6","opensearchproject/opensearch:2.19.6"),
 "os3":("opensearch-3","3.8.0","opensearchproject/opensearch:3.8.0"),
}
def run(*args): return subprocess.check_output(args,text=True).strip()
def request(port,path,body=None,method=None,content_type="application/json"):
 data=body.encode() if isinstance(body,str) else json.dumps(body).encode() if body is not None else None
 req=urllib.request.Request(f"http://127.0.0.1:{port}{path}",data=data,method=method,headers={"Content-Type":content_type})
 try:
  with urllib.request.urlopen(req,timeout=20) as response: return response.status,json.load(response)
 except urllib.error.HTTPError as error: return error.code,json.load(error)
def require(ok,message):
 if not ok: raise RuntimeError(message)
def check(target):
 module,compiled,image=TARGETS[target]
 paths=list((ROOT/"adapters"/module/"build"/compiled/"distributions").glob(f"*-{compiled}.zip"))
 require(len(paths)==1,f"Build the exact {compiled} artifact first: {paths}")
 artifact=paths[0]; name="sharddeck-homoglyph-it-"+target; tag="sharddeck-homoglyph-it:"+target
 host="opensearch" if target.startswith("os") else "elasticsearch"
 with tempfile.TemporaryDirectory(prefix="homoglyph-image-") as tmp:
  shutil.copyfile(artifact,Path(tmp)/"plugin.zip")
  (Path(tmp)/"Dockerfile").write_text(f"FROM {image}\nCOPY --chown=1000:0 plugin.zip /tmp/homoglyph.zip\nRUN /usr/share/{host}/bin/{host}-plugin install --batch file:///tmp/homoglyph.zip && rm /tmp/homoglyph.zip\n")
  subprocess.run(["docker","build","--quiet","--tag",tag,tmp],check=True)
 env={"discovery.type":"single-node","node.store.allow_mmap":"false","cluster.name":"homoglyph-integration",
      ("OPENSEARCH_JAVA_OPTS" if host=="opensearch" else "ES_JAVA_OPTS"):"-Xms512m -Xmx512m"}
 if host=="opensearch": env.update(DISABLE_INSTALL_DEMO_CONFIG="true",DISABLE_SECURITY_PLUGIN="true")
 else:
  env.update({"xpack.security.enabled":"false","xpack.ml.enabled":"false","ingest.geoip.downloader.enabled":"false"})
  if not target.startswith("es7"): env["xpack.security.autoconfiguration.enabled"]="false"
 args=["docker","run","--detach","--name",name,"--label","io.sharddeck.homoglyph.qa=true","--memory","2g","--memory-swap","2g","--cpus","2","--pids-limit","512","--publish","127.0.0.1::9200"]
 for key,value in env.items(): args.extend(["--env",key+"="+value])
 container=run(*args,tag)
 result={"target":target,"image":image,"artifact":str(artifact.relative_to(ROOT)),"artifact_sha256":hashlib.sha256(artifact.read_bytes()).hexdigest()}
 try:
  inspected=json.loads(run("docker","inspect",container))[0]
  result["image_id"]=inspected["Image"]
  port=inspected["NetworkSettings"]["Ports"]["9200/tcp"][0]["HostPort"]
  deadline=time.monotonic()+240
  while True:
   try:
    code,health=request(port,"/_cluster/health")
    if code==200 and health["status"] in ("green","yellow"): break
   except (OSError,ValueError): pass
   require(time.monotonic()<deadline,"Engine did not become healthy")
   time.sleep(2)
  _,info=request(port,"/"); result["version"]=info["version"]["number"]; result["lucene"]=info["version"]["lucene_version"]
  require(result["version"]==image.rsplit(":",1)[1],"Running engine version differs from requested image tag")
  def analyze(text,settings=None):
   config={"type":"homoglyph"}; config.update(settings or {})
   return request(port,"/_analyze",{"tokenizer":"keyword","filter":[config],"text":text})
  code,body=request(port,"/_analyze",{"tokenizer":"keyword","filter":["homoglyph"],"text":"о"})
  require(code==200 and [x["token"] for x in body["tokens"]]==["o"],"Bare filter registration failed: "+str(body))
  for text,settings,expected in [
    ("tℯst😀",{},["test😀"]),("о",{},["o"]),("о"*30,{},["o"*30]),
    ("о",{"preserve_original":True},["о","o"]),
    ("microsoft",{"profile":"unicode_search_v1"},["rnicrosoft"]),
    ("he\u0301llo",{"profile":"unicode_search_v1"},["hello"]),
    ("\u200b",{"profile":"unicode_search_v1"},[])]:
   code,body=analyze(text,settings); require(code==200 and [x["token"] for x in body["tokens"]]==expected,f"Analysis mismatch: {body}")
  for text,settings,expected in [
    ("éñ", {"profile":"unicode_expand_v1"}, ["éñ","éñ","éñ"]),
    ("éñ", {"profile":"unicode_expand_v1","max_changed_positions":2}, ["éñ","éñ","éñ","éñ"]),
    ("😀é", {"profile":"unicode_expand_v1"}, ["😀é","😀é"]),
    ("é", {"profile":"unicode_expand_v1","preserve_original":True}, ["é","é"])]:
   code,body=analyze(text,settings)
   require(code==200 and [x["token"] for x in body["tokens"]]==expected,"Expansion mismatch: "+str(body))
   require([x["position"] for x in body["tokens"]]==[0]*len(expected),"Expansion positions changed")
  code,body=analyze("rn",{"profile":"unicode_expand_v1"})
  require(code==200 and len(body["tokens"])==18 and {"rn","m","𝐦"}.issubset({x["token"] for x in body["tokens"]}),"Sequence expansion failed")
  for text,settings in [("o😀"*30,{"profile":"unicode_expand_v1"}),("é",{"profile":"unicode_expand_v1","max_output_tokens_per_input":1}),
    ("é",{"max_changed_positions":5}),("é",{"max_output_tokens_per_input":1025}),
    ("éñ",{"profile":"unicode_expand_v1","max_changed_positions":2,"max_output_tokens_per_input":3}),
    ("x"*4097,{}),("x",{"max_input_utf16_units":5000}),("x",{"profile":"bad"}), ("x",{"max_payload_bytes":4097})]:
   code,body=analyze(text,settings); require(code>=400,f"Unsafe/invalid configuration accepted: {settings}")
  code,_=analyze("x",{"misspelled_option":1})
  result["rejects_unknown_settings"]=code>=400
  require(result["rejects_unknown_settings"] or "stable" in module,"Classic adapter ignored unknown setting")
  index={"settings":{"number_of_shards":1,"number_of_replicas":0,"analysis":{"filter":{"h":{"type":"homoglyph","profile":"unicode_search_v1"}},"analyzer":{"h":{"tokenizer":"keyword","filter":["h"]}}}},"mappings":{"properties":{"value":{"type":"text","analyzer":"h"}}}}
  code,body=request(port,"/homoglyph-qa",index,"PUT"); require(code==200,str(body))
  code,body=request(port,"/homoglyph-qa/_doc/1?refresh=true",{"value":"pаypаl"},"PUT"); require(code in (200,201),str(body))
  code,body=request(port,"/homoglyph-qa/_search",{"query":{"match":{"value":"paypal"}}}); require(code==200 and body["hits"]["total"]["value"]==1,str(body))
  preserved_index=json.loads(json.dumps(index)); preserved_index["settings"]["analysis"]["filter"]["h"]["preserve_original"]=True
  code,body=request(port,"/homoglyph-preserved-qa",preserved_index,"PUT"); require(code==200,str(body))
  code,body=request(port,"/homoglyph-preserved-qa/_doc/1?refresh=true",{"value":"о"},"PUT"); require(code in (200,201),str(body))
  for value in ["ordinary","о","ordinary","о"]:
   code,body=request(port,"/homoglyph-preserved-qa/_search?allow_partial_search_results=false",{"query":{"match":{"value":value}}})
   require(code==200 and body["_shards"]["failed"]==0 and (value!="о" or body["hits"]["total"]["value"]==1),str(body))
  expanded_index=json.loads(json.dumps(index)); expanded_index["settings"]["analysis"]["filter"]["h"]={"type":"homoglyph","profile":"unicode_expand_v1"}
  code,body=request(port,"/homoglyph-expanded-qa",expanded_index,"PUT"); require(code==200,str(body))
  code,body=request(port,"/homoglyph-expanded-qa/_doc/1?refresh=true",{"value":"m"},"PUT"); require(code in (200,201),str(body))
  for value in ["rn","m","𝐦","rn"]:
   code,body=request(port,"/homoglyph-expanded-qa/_search?allow_partial_search_results=false",{"query":{"match":{"value":value}}})
   require(code==200 and body["_shards"]["failed"]==0 and body["hits"]["total"]["value"]==1,str(body))
  expansion_bulk="\n".join(json.dumps(x) for x in [{"index":{"_index":"homoglyph-expanded-qa","_id":"bad"}},{"value":"o😀"*30},
    {"index":{"_index":"homoglyph-expanded-qa","_id":"good"}},{"value":"é"}])+"\n"
  code,body=request(port,"/_bulk?refresh=true",expansion_bulk,content_type="application/x-ndjson")
  require(code==200 and body["items"][0]["index"]["status"]>=400 and body["items"][1]["index"]["status"]==201,"Expansion bulk failure isolation failed")
  code,body=request(port,"/homoglyph-expanded-qa/_search?allow_partial_search_results=false",{"query":{"match":{"value":"o😀"*30}}})
  require(code>=400,"Expansion over-budget query succeeded")
  bulk="\n".join(json.dumps(x) for x in [{"index":{"_index":"homoglyph-qa","_id":"bad"}},{"value":"x"*4097},{"index":{"_index":"homoglyph-qa","_id":"good"}},{"value":"hello"}])+"\n"
  code,body=request(port,"/_bulk?refresh=true",bulk,content_type="application/x-ndjson")
  require(code==200 and body["items"][0]["index"]["status"]>=400 and body["items"][1]["index"]["status"]==201,"Bulk failure isolation failed")
  code,body=request(port,"/homoglyph-qa/_search?allow_partial_search_results=false",{"query":{"match":{"value":"x"*4097}}})
  require(code>=400 or (body.get("_shards",{}).get("failed",0)>0),"Oversized analyzed query succeeded")
  code,health=request(port,"/_cluster/health"); require(code==200 and health["status"] in ("green","yellow"),"Node unhealthy after rejection")
  _,stats=request(port,"/_nodes/_local/stats/jvm"); node=next(iter(stats["nodes"].values())); result["heap_max_bytes"]=node["jvm"]["mem"]["heap_max_in_bytes"]
  result["status"]="passed"
 finally:
  subprocess.run(["docker","stop","--time","90",container],check=True,stdout=subprocess.DEVNULL)
  state=json.loads(run("docker","inspect",container))[0]["State"]
  result["oom_killed"]=state["OOMKilled"]; result["exit_code"]=state["ExitCode"]
  logs=run("docker","logs","--tail","60",container)
  (ROOT/"qa/integration"/(target+".log")).write_text(logs)
  subprocess.run(["docker","rm",container],check=True,stdout=subprocess.DEVNULL)
  require(not state["OOMKilled"] and state["ExitCode"] in (0,143),"Engine did not stop cleanly")
 return result
def main():
 parser=argparse.ArgumentParser(description=__doc__); parser.add_argument("targets",nargs="*")
 parser.add_argument("--output",type=Path,default=ROOT/"qa/integration/verification.json",help="JSON report path; replaced during the run")
 args=parser.parse_args()
 targets=args.targets or list(TARGETS)
 if any(x not in TARGETS for x in targets): parser.error("Unknown target")
 args.output.parent.mkdir(parents=True,exist_ok=True)
 report={"checked_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"results":[]}
 for target in targets:
  print("Checking "+target,flush=True)
  try: result=check(target)
  except Exception as error: result={"target":target,"status":"failed","error":str(error)}
  report["results"].append(result)
  args.output.write_text(json.dumps(report,indent=2)+"\n")
  print(json.dumps(result),flush=True)
 return int(any(x["status"]!="passed" for x in report["results"]))
if __name__=="__main__": raise SystemExit(main())
