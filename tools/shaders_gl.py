#!/usr/bin/env python3
"""Sillage: the OpenGL ES 3.00 / WebGL 2 shaders, made from the Vulkan ones.

    tools/shaders_gl.py            writes engine/src/sillage/gl/GlShaders.kt
    tools/shaders_gl.py --stdout   prints it instead (the test compares it with the file)
    tools/shaders_gl.py --dump DIR writes each shader as DIR/name (to try them in a compiler)

The sources stay the ones of engine/resources/shaders (GLSL 4.50 for Vulkan); what changes:
  - `#version 300 es` and the precisions; `#include` pasted in; comments and blank lines dropped;
  - push constants become a std140 uniform block of the same name and members, and the blocks and
    samplers lose their set and binding (the renderer ties them by name);
  - what goes from the vertex shader to the fragment shader is named after its location (v0, v1 …):
    Vulkan matches them by location, OpenGL ES 3.00 by name;
  - gl_VertexIndex and gl_InstanceIndex take their OpenGL names;
  - the vertex shaders end with `gl_Position.z = 2.0 * gl_Position.z - gl_Position.w`: OpenGL's depth
    runs from -1 to 1, Vulkan's from 0 to 1. Nothing is turned upside down here: the renderer does it once.
Only Python's own library is used.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHADERS = os.path.join(ROOT, "engine", "resources", "shaders")
OUTPUT = os.path.join(ROOT, "engine", "src", "sillage", "gl", "GlShaders.kt")

HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
"""

BLOCK = re.compile(r"uniform\s+\w+\s*\{[^}]*\}")


def fail(message):
    sys.exit("shaders_gl.py: " + message)


def read(name):
    with open(os.path.join(SHADERS, name), encoding="utf-8") as f:
        return f.read()


def inlined(name, seen=()):
    """The file with its includes pasted in, without #version and #extension lines."""
    if name in seen:
        fail("include loop through " + name)
    out = []
    for line in read(name).split("\n"):
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            out.append(inlined(m.group(1), seen + (name,)))
        elif re.match(r"\s*#(version|extension)\b", line):
            continue
        else:
            out.append(line)
    return "\n".join(out)


def without_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return "\n".join(line.rstrip() for line in text.split("\n") if line.strip())


def renamed(text, old, new):
    """Every use of the identifier [old] becomes [new], except members (after a dot) and inside uniform blocks."""
    word = re.compile(r"(?<![\w.])" + re.escape(old) + r"\b")
    out = []
    at = 0
    for block in BLOCK.finditer(text):
        out.append(word.sub(new, text[at:block.start()]))
        out.append(block.group(0))
        at = block.end()
    out.append(word.sub(new, text[at:]))
    return "".join(out)


def converted(name):
    vertex = name.endswith(".vert")
    text = without_comments(inlined(name))
    # Push constants: a uniform block with the same members.
    text = re.sub(r"layout\s*\(\s*push_constant\s*\)\s*uniform", "layout(std140) uniform", text)
    # Sets and bindings: blocks keep std140, samplers are plain uniforms.
    text = re.sub(r"layout\s*\(\s*set\s*=\s*\d+\s*,\s*binding\s*=\s*\d+\s*\)\s*uniform(\s+\w+\s*\{)", r"layout(std140) uniform\1", text)
    text = re.sub(r"layout\s*\(\s*set\s*=\s*\d+\s*,\s*binding\s*=\s*\d+\s*\)\s*uniform", "uniform", text)
    if "binding" in text or "push_constant" in text:
        fail(name + ": a layout this tool does not know")
    # Varyings: named after their location on both sides.
    direction = "out" if vertex else "in"
    varying = re.compile(r"layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*((?:flat\s+)?)" + direction + r"\s+(\w+)\s+(\w+)\s*;")
    found = varying.findall(text)
    text = varying.sub(lambda m: "%s%s %s %s;" % (m.group(2), direction, m.group(3), m.group(4)), text)
    for location, _, _, identifier in found:
        new = "v" + location
        if re.search(r"(?<![\w.])" + new + r"\b", text):
            fail("%s: %s is already a name there" % (name, new))
        text = renamed(text, identifier, new)
    text = text.replace("gl_VertexIndex", "gl_VertexID").replace("gl_InstanceIndex", "gl_InstanceID")
    if vertex:
        # OpenGL's clip depth: appended at the end of main, the last function of every vertex shader.
        m = re.search(r"void\s+main\s*\(\s*\)\s*\{", text)
        end = text.rstrip().rfind("}")
        if not m or end < m.end() or text.count("gl_Position") == 0:
            fail(name + ": main not found")
        text = text[:end] + "    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;\n}"
    text = HEADER + text.strip() + "\n"
    if '"""' in text or "$" in text or not text.isascii():
        fail(name + ": a character Kotlin's raw strings or GLSL ES would not take")
    return text


def names():
    return sorted(f for f in os.listdir(SHADERS) if f.endswith(".vert") or f.endswith(".frag"))


def constant(name):
    return name.replace(".", "_").upper()


def kotlin():
    out = ["// Made by tools/shaders_gl.py from engine/resources/shaders: do not edit, run the tool again.",
           "package sillage.gl",
           "",
           "/**",
           " * The shaders in GLSL ES 3.00 (OpenGL ES 3.0, WebGL 2), made from the Vulkan sources so that the two",
           " * renderers draw with the same code. A test checks that this file is what the tool gives.",
           " */",
           "internal object GlShaders {"]
    for name in names():
        out.append("    const val %s = \"\"\"%s\"\"\"" % (constant(name), converted(name)))
        out.append("")
    out.append("    /** By file name (« toon.vert »). */")
    out.append("    fun source(name: String): String = when (name) {")
    for name in names():
        out.append("        \"%s\" -> %s" % (name, constant(name)))
    out.append("        else -> throw IllegalArgumentException(\"No shader named \" + name)")
    out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def main():
    if "--stdout" in sys.argv:
        sys.stdout.write(kotlin())
    elif "--dump" in sys.argv:
        target = sys.argv[sys.argv.index("--dump") + 1]
        os.makedirs(target, exist_ok=True)
        for name in names():
            with open(os.path.join(target, name), "w", encoding="utf-8") as f:
                f.write(converted(name))
    else:
        with open(OUTPUT, "w", encoding="utf-8") as f:
            f.write(kotlin())
        print("written: " + os.path.relpath(OUTPUT, ROOT))


if __name__ == "__main__":
    main()
