# Taken over from 3d4j

The packages under `src/main/java/com/aresstack/threed4j` (`ffm`, `gl`, `math`, `platform`,
`gfx` without the scene helpers) and `src/main/resources/shaders/blit.hlsl` are copied unchanged
from [aresstack/3d4j](https://github.com/aresstack/3d4j), commit `62ce975`. They open a native
window and render into it with Direct3D 11 (Windows, WARP as fallback) or OpenGL 3.3 (Windows,
Linux/X11) through the Foreign Function & Memory API of Java 21 (preview).

Changes belong in 3d4j first; copy them over again from there. What the visualizer adds lives
in `com.aresstack.audio.gpu` and in the shaders `visual.*`.
