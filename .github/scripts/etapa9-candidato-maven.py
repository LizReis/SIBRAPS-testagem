"""Prepare an explicit Maven candidate in the ephemeral checkout; never publish."""
import pathlib
import subprocess
import json
import hashlib

pom=pathlib.Path("pom.xml")
original=pom.read_bytes()
out=pathlib.Path("auditoria-etapa9")
out.mkdir(exist_ok=True)
text=original.decode("utf-8")
def replace_once(old,new):
    global text
    assert text.count(old)==1, old
    text=text.replace(old,new,1)

# Spring Boot 4.0.8 BOM aligns Spring Framework 7.0.9 and Spring Data.
# Targeted overrides address the additional fixed floors in the collected OSV advisories.
replace_once("<artifactId>spring-boot-starter-parent</artifactId>\n        <version>4.0.6</version>",
             "<artifactId>spring-boot-starter-parent</artifactId>\n        <version>4.0.8</version>")
props={"tomcat.version":"11.0.25","jackson-2-bom.version":"2.21.7","jackson-bom.version":"3.1.7",
       "logback.version":"1.5.34","log4j2.version":"2.26.1"}
replace_once("        <java.version>21</java.version>",
             "        <java.version>21</java.version>\n"+
             "\n".join("        <"+k+">"+v+"</"+k+">" for k,v in props.items()))
batik=["anim","awt-util","bridge","codec","constants","css","dom","ext","gvt","i18n","parser","script",
       "shared-resources","svg-dom","svggen","transcoder","util","xml"]
managed=[("org.apache.xmlgraphics","batik-"+a,"1.17") for a in batik]+[("xalan",a,"2.7.3") for a in ["xalan","serializer"]]
block="    <dependencyManagement>\n        <dependencies>\n"
for group,artifact,version in managed:
    block+=f"            <dependency>\n                <groupId>{group}</groupId>\n                <artifactId>{artifact}</artifactId>\n                <version>{version}</version>\n            </dependency>\n"
block+="        </dependencies>\n    </dependencyManagement>\n"
replace_once("    <dependencies>\n",block+"    <dependencies>\n")
candidate=text.encode("utf-8")
(out/"pom-candidato.xml").write_bytes(candidate)
(out/"sha256.json").write_text(json.dumps({"original":hashlib.sha256(original).hexdigest(),"candidate":hashlib.sha256(candidate).hexdigest()},indent=2))
try:
    pom.write_bytes(candidate)
    subprocess.run(["bash","./mvnw","-B","-ntp","org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree",
                    "-DoutputType=json","-DoutputFile=auditoria-etapa9/dependency-tree.json"],check=True)
    subprocess.run(["python3","../../.github/scripts/etapa9-auditoria-maven.py"],check=True)
    inventory=json.loads((out/"inventory.json").read_text())
    versions={x["name"]:x["version"] for x in inventory}
    for group,artifact,version in managed:
        name=group+":"+artifact
        if name in versions:
            assert versions[name]==version, (name,versions[name],version)
    expected={"org.springframework.boot:spring-boot-autoconfigure":"4.0.8",
              "org.apache.tomcat.embed:tomcat-embed-core":"11.0.25",
              "com.fasterxml.jackson.core:jackson-core":"2.21.7",
              "tools.jackson.core:jackson-core":"3.1.7",
              "ch.qos.logback:logback-core":"1.5.34","org.apache.logging.log4j:log4j-api":"2.26.1"}
    for name,version in expected.items():
        assert versions.get(name)==version,(name,versions.get(name),version)
    report=json.loads((out/"osv-report.json").read_text())
    base=json.loads(pathlib.Path("../../.github/scripts/etapa9-maven-baseline.json").read_text())
    old={v["id"] for x in base["matches"] for v in x["vulnerabilities"]}
    new={v["id"] for x in report["matches"] for v in x["vulnerabilities"]}
    comparison={"baseline":len(old),"candidate":len(new),"remaining":sorted(old&new),"new":sorted(new-old),
                "noLongerMatched":sorted(old-new),"note":"OSV match counts, not a security acceptance."}
    (out/"comparison.json").write_text(json.dumps(comparison,indent=2))
    print("MAVEN_CANDIDATE "+json.dumps(comparison))
finally:
    pom.write_bytes(original)
