package com.sadurni.attacca.dsp

/**
 * Added to the state of recursive filters: a channel that falls digitally silent would otherwise
 * decay into denormal numbers, which some processors handle many times slower (stutters).
 */
const val ANTI_DENORMAL = 1e-20
const val ANTI_DENORMAL_F = 1e-20f
