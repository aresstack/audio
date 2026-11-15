/**
 * Sound in three dimensions for headphones (work package D4): head-related impulse responses on
 * a grid ({@link com.aresstack.audio.core.spatial.HrirSet}), computed from a spherical head model
 * or read from a measured set, and the {@link com.aresstack.audio.core.spatial.BinauralPanner}
 * that places a moving source with them. The core knows directions, not stages: whoever owns the
 * stage turns a place into azimuth, elevation and distance gain.
 */
package com.aresstack.audio.core.spatial;
