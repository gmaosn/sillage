#!/usr/bin/env python3
"""Compile les blocs Kotlin de la documentation (README.md, docs/*.md) contre le moteur compilé.

    python3 -I tools/verifier_docs.py            (après ./kotlin build -m engine)

Un bloc qui commence par « package » ou contient « fun main » est un programme entier ; les autres sont
des extraits, compilés dans le corps d'une fonction dont le receveur est une Scene, avec en paramètres
les noms qui reviennent dans les exemples (banner, door, lift…). Les blocs qui contiennent « … » (des
trous laissés au lecteur) sont sautés, et comptés. Sortie : un résumé, puis les erreurs par bloc
(fichier et ligne du bloc dans la documentation). Code de sortie 1 s'il y a une erreur.
"""
import glob
import os
import re
import shutil
import subprocess
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
work = os.path.join(root, "build", "tmp", "docscheck")
m2 = os.path.join(root, ".tools", "shared", ".m2.cache", "org", "jetbrains")

# Les noms des exemples, avec un type plausible, en déclarations de premier niveau : à l'intérieur d'un
# onUpdate { }, la propriété du nœud (position…) l'emporte sur elles, comme dans un vrai jeu.
STUBS = """
val banner: Hud.Text get() = TODO(); val title: Hud.Text get() = TODO(); val ready: Hud.Text get() = TODO()
val door: Node get() = TODO(); val lift: Node get() = TODO(); val star: Node get() = TODO(); val sign: Node get() = TODO()
val crate: Node get() = TODO(); val plank: Node get() = TODO(); val seat: Node get() = TODO(); val ball: Node get() = TODO()
val node: Node get() = TODO(); val wall: Node get() = TODO(); val torch: Node get() = TODO(); val shell: Node get() = TODO()
val gull: Node get() = TODO(); val lantern: Node get() = TODO(); val house: Node get() = TODO(); val village: Node get() = TODO()
val at: Vec3 get() = TODO(); val from: Vec3 get() = TODO(); val to: Vec3 get() = TODO(); val pivot: Vec3 get() = TODO()
val origin: Vec3 get() = TODO(); val direction: Vec3 get() = TODO(); val position: Vec3 get() = TODO(); val fire: Vec3 get() = TODO()
val score: Int get() = TODO(); val sound: Sound get() = TODO(); val cry: Sound get() = TODO(); val creak: Sound get() = TODO()
val mesh: Mesh get() = TODO(); val width: Float get() = TODO(); val world: PhysicsWorld get() = TODO(); val dt: Float get() = TODO()
val shape: Shape get() = TODO(); val triangles: FloatArray get() = TODO(); val materialIds: IntArray get() = TODO()
val cabin: Body get() = TODO()
fun openDoor() {}
"""
IMPORTS = """import sillage.*
import sillage.audio.*
import sillage.math.*
import sillage.physics.*
import kotlin.math.*
"""


def newest(pattern):
    found = sorted(p for p in glob.glob(os.path.join(m2, pattern)) if "sources" not in p)
    if not found:
        sys.exit("introuvable : " + pattern + " (lancer ./kotlin build une fois)")
    return found[-1]


def jdk():
    dirs = sorted(glob.glob(os.path.join(root, ".tools", "shared", "extract.cache", "*", "jdk-2[5-9]*")),
                  key=lambda d: os.path.basename(d))
    if not dirs:
        sys.exit("pas de JDK dans .tools")
    return dirs[-1]


def blocks():
    files = [os.path.join(root, "README.md")] + sorted(glob.glob(os.path.join(root, "docs", "*.md")))
    for f in files:
        lines = open(f, encoding="utf-8").read().split("\n")
        i = 0
        while i < len(lines):
            if lines[i].strip() == "```kotlin":
                start = i + 1
                j = start
                while j < len(lines) and lines[j].strip() != "```":
                    j += 1
                yield os.path.relpath(f, root), start + 1, "\n".join(lines[start:j])
                i = j
            i += 1


def main():
    shutil.rmtree(work, ignore_errors=True)
    src = os.path.join(work, "src")
    os.makedirs(src)
    kept, skipped = [], 0
    open(os.path.join(src, "Stubs.kt"), "w", encoding="utf-8").write("package docscheck\n" + IMPORTS + STUBS)
    for n, (f, line, code) in enumerate(blocks()):
        if "…" in code:
            skipped += 1
            continue
        whole = code.lstrip().startswith("package") or "fun main" in code
        # Les étapes du tutoriel s'appuient sur les précédentes : son programme entier les contient toutes.
        if f.endswith("TUTORIAL.md") and not whole:
            skipped += 1
            continue
        # Une signature seule (la référence) n'a pas de corps à compiler.
        if all(l.startswith("fun ") or not l.strip() for l in code.split("\n")) and "{" not in code:
            skipped += 1
            continue
        name = "Bloc%03d" % n
        if whole:
            body = re.sub(r"^package .*$", "", code, count=1, flags=re.M)
            text = "@file:JvmName(\"%s\")\npackage docscheck.%s\n%s\n%s\n" % (name, name.lower(), IMPORTS, body)
        else:
            text = "package docscheck\n%s\n@Suppress(\"UNUSED\", \"UNUSED_VARIABLE\")\nfun Scene.%s() {\n%s\n}\n" % (
                IMPORTS, name.lower(), code)
        path = os.path.join(src, name + ".kt")
        open(path, "w", encoding="utf-8").write(text)
        kept.append((name, f, line))
    engine = os.path.join(root, "build", "artifacts", "CompiledJvmArtifact", "enginejvm", "kotlin-output")
    stdlib = newest("kotlin/kotlin-stdlib/*/kotlin-stdlib-[0-9]*.jar")
    cp = ":".join([newest("kotlin/kotlin-compiler-embeddable/*/kotlin-compiler-embeddable-[0-9]*.jar"), stdlib,
                   newest("kotlin/kotlin-script-runtime/*/kotlin-script-runtime-[0-9]*.jar"),
                   newest("kotlin/kotlin-reflect/*/kotlin-reflect-[0-9]*.jar"),
                   newest("kotlin/kotlin-daemon-embeddable/*/kotlin-daemon-embeddable-[0-9]*.jar"),
                   newest("kotlinx/kotlinx-coroutines-core-jvm/*/kotlinx-coroutines-core-jvm-[0-9]*.jar"),
                   os.path.join(m2, "annotations", "13.0", "annotations-13.0.jar")])
    home = jdk()
    run = subprocess.run([os.path.join(home, "bin", "java"), "--sun-misc-unsafe-memory-access=allow", "-cp", cp,
                          "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", src, "-d", os.path.join(work, "out"),
                          "-classpath", engine + ":" + stdlib, "-no-stdlib", "-no-reflect", "-jdk-home", home,
                          "-nowarn"], capture_output=True, text=True)
    errors = {}
    for l in (run.stdout + run.stderr).splitlines():
        m = re.match(r".*/(Bloc\d+)\.kt:(\d+):\d+: error: (.*)", l)
        if m:
            errors.setdefault(m.group(1), []).append(m.group(3))
    print("blocs compilés : %d, sautés (« … », étapes du tutoriel, signatures) : %d, en erreur : %d" % (len(kept), skipped, len(errors)))
    for name, f, line in kept:
        if name in errors:
            print("  %s:%d" % (f, line))
            for e in errors[name][:4]:
                print("      " + e)
    if run.returncode != 0 and not errors:
        print(run.stdout + run.stderr)
    sys.exit(1 if errors or run.returncode != 0 else 0)


main()
