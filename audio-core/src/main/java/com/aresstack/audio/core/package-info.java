/**
 * The audio core: sound as blocks of float samples, pulled through a graph of sources and
 * processors by whichever thread wants the next block. The same chain serves the offline mix
 * ({@code mix}) and live playback ({@code live}: channels that move while they play, gliding
 * parameters, one audio thread pulling into a {@code LiveSink}; {@code line}: the sound card).
 *
 * <p>Rules for every processor (binding, architecture review section 4b.1):
 * <ol>
 *   <li>{@code prepare(sampleRate, maxBlockFrames)} allocates everything; {@code process} neither
 *       allocates nor locks nor throws.</li>
 *   <li>Parameters are checked outside the audio thread and handed over as an immutable value;
 *       a change keeps the state of filters and delay lines.</li>
 *   <li>Nothing is clamped between stages; float values above 1.0 are allowed up to the limiter
 *       or the {@link com.aresstack.audio.core.AudioSink}.</li>
 *   <li>Measurements reach a user interface through exactly one writer, without locks.</li>
 *   <li>Every processor reports its latency in frames.</li>
 * </ol>
 *
 * <p>{@link com.aresstack.audio.core.BiquadCoefficients} is taken over unchanged from AskAI's
 * {@code audio-dsp} module ({@code com.aresstack.audio.dsp.BiquadCoefficients}, MIT, AskAI
 * {@code develop} {@code 5b788e2}); only its package differs.
 */
package com.aresstack.audio.core;
