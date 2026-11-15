/**
 * The model of a piece of music, stage 1 of work package F1.
 *
 * <p>A {@link com.aresstack.audio.composition.Composition} holds its {@code Meta}, its time
 * ({@link com.aresstack.audio.composition.TimeMap}), its form ({@code Section}), its players
 * ({@link com.aresstack.audio.composition.Part} with their {@code Voice}s) and the lanes of the
 * arrangement ({@link com.aresstack.audio.composition.Track}) with their
 * {@link com.aresstack.audio.composition.Clip}s. Every object is immutable and has a short,
 * stable id; names are for display only. Time is counted in ticks at
 * {@link com.aresstack.audio.composition.TimeMap#PPQ} per quarter.
 *
 * <p>Binding rule: the model knows nothing of any AI generator, nor of files, windows or sound.
 * The package depends on the JDK alone; an architecture test keeps it so.
 */
package com.aresstack.audio.composition;
