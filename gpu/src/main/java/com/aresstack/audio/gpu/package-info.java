/**
 * The full screen of the visualizer on the graphics card ({@link com.aresstack.audio.gpu.GpuScreen}).
 * The classes of this package use no preview feature, so the studio compiles against them as usual;
 * the native part ({@code internal}) needs a JVM started with {@code --enable-preview} and
 * {@code --enable-native-access=ALL-UNNAMED} on Java 21. Where that is missing, {@link
 * com.aresstack.audio.gpu.GpuScreen#open} says why and the studio draws with Java2D.
 */
package com.aresstack.audio.gpu;
