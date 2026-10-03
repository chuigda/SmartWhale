// Fallback build without Gradle/NeoForm: compiles against the jars an installed
// NeoForge instance already has. Usage: node quickbuild.mjs [mcRoot] [versionId] [jdkHome]
// Output: build/libs/smartwhale-bridge-<version>.jar (same path as the Gradle build).
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const here = import.meta.dirname;
const mcRoot = process.argv[2] ?? "D:\\.minecraft";
const versionId = process.argv[3] ?? "1.21.1-NeoForge";
const jdk = process.argv[4] ?? "D:\\Applications\\JDK21";

const props = Object.fromEntries(
  fs.readFileSync(path.join(here, "gradle.properties"), "utf8").split(/\r?\n/)
    .filter((l) => l && !l.startsWith("#") && l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]),
);
const libs = path.join(mcRoot, "libraries");
const version = JSON.parse(fs.readFileSync(path.join(mcRoot, "versions", versionId, `${versionId}.json`), "utf8"));

function mavenPath(name) {
  const [group, artifact, ver, classifier] = name.split(":");
  const file = `${artifact}-${ver}${classifier ? `-${classifier}` : ""}.jar`;
  return path.join(libs, ...group.split("."), artifact, ver, file);
}

const mcVer = props.minecraft_version;
const neoVer = props.neo_version;
const neoformDir = fs.readdirSync(path.join(libs, "net", "minecraft", "client")).find((d) => d.startsWith(`${mcVer}-`));
if (!neoformDir) throw new Error(`No client jars for ${mcVer} under ${libs}`);
// Patched NeoForge classes must shadow vanilla ones.
const classpath = [
  path.join(libs, "net", "neoforged", "neoforge", neoVer, `neoforge-${neoVer}-client.jar`),
  path.join(libs, "net", "neoforged", "neoforge", neoVer, `neoforge-${neoVer}-universal.jar`),
  path.join(libs, "net", "minecraft", "client", neoformDir, `client-${neoformDir}-srg.jar`),
];
// compileOnly mod APIs (same jars as build.gradle).
const compileOnly = path.join(here, "..", "libs");
if (fs.existsSync(compileOnly)) {
  for (const f of fs.readdirSync(compileOnly)) if (f.endsWith(".jar")) classpath.push(path.join(compileOnly, f));
}
for (const lib of version.libraries ?? []) {
  const p = lib.downloads?.artifact?.path ? path.join(libs, lib.downloads.artifact.path) : mavenPath(lib.name);
  if (fs.existsSync(p) && !classpath.includes(p)) classpath.push(p);
}

const build = path.join(here, "build");
const classes = path.join(build, "quick", "classes");
fs.rmSync(path.join(build, "quick"), { recursive: true, force: true });
fs.mkdirSync(classes, { recursive: true });

const sources = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (p.endsWith(".java")) sources.push(p);
  }
})(path.join(here, "src", "main", "java"));

const argfile = path.join(build, "quick", "javac.args");
fs.writeFileSync(argfile, [
  "-encoding", "UTF-8", "--release", "21", "-nowarn", "-proc:none",
  "-d", classes, "-cp", classpath.join(path.delimiter), ...sources,
].map((a) => `"${a.replaceAll("\\", "\\\\")}"`).join("\n"));
execFileSync(path.join(jdk, "bin", "javac"), [`@${argfile}`], { stdio: "inherit" });

// Expand ${...} placeholders like Gradle's generateModMetadata task.
for (const dir of ["templates", "resources"]) {
  const root = path.join(here, "src", "main", dir);
  if (!fs.existsSync(root)) continue;
  (function copy(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      const out = path.join(classes, path.relative(root, p));
      if (e.isDirectory()) { fs.mkdirSync(out, { recursive: true }); copy(p); continue; }
      let content = fs.readFileSync(p);
      if (dir === "templates") {
        content = content.toString("utf8").replace(/\$\{(\w+)\}/g, (m, k) => {
          if (!(k in props)) throw new Error(`Unknown template property ${k} in ${p}`);
          return props[k];
        });
      }
      fs.writeFileSync(out, content);
    }
  })(root);
}

const jar = path.join(build, "libs", `smartwhale-bridge-${props.mod_version}.jar`);
fs.mkdirSync(path.dirname(jar), { recursive: true });
fs.rmSync(jar, { force: true });
execFileSync(path.join(jdk, "bin", "jar"), ["--create", "--file", jar, "-C", classes, "."], { stdio: "inherit" });
console.log(`Built ${jar}`);
