package com.aresstack.threed4j.gfx.d3d11;

import static org.junit.Assume.assumeTrue;

import com.aresstack.threed4j.gfx.ShaderSource;
import org.junit.Test;

/**
 * The HLSL of the visualizer compiles with the compiler of Windows (d3dcompiler_47), for both
 * stages and the shader model the device uses. Runs on Windows only (the CI job gpu-windows);
 * the GLSL twin is compiled wherever OpenGL is.
 */
public final class VisualShaderCompilesTest {

    @Test
    public void visualHlslCompiles() {
        assumeTrue("Windows only", System.getProperty("os.name", "").startsWith("Windows"));
        String source = ShaderSource.read("/shaders/visual.hlsl");
        D3D11Api.compile(source, "visual.hlsl", "vertexMain", "vs_4_0").release();
        D3D11Api.compile(source, "visual.hlsl", "pixelMain", "ps_4_0").release();
    }
}
