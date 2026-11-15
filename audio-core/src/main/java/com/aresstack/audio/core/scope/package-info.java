/**
 * What a picture of the sound needs from playback: the frames the listener hears right now
 * ({@link com.aresstack.audio.core.scope.SoundTap}). The audio thread hands each block over without
 * locking and without allocating; a picture thread reads the latest frames whenever it draws.
 */
package com.aresstack.audio.core.scope;
