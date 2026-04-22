/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation;


import com.dfsek.seismic.math.numericanalysis.interpolation.InterpolationFunctions;


/**
 * Trilinear interpolator storing 8 corner values on a unit cube.
 * Delegates to {@link InterpolationFunctions#triLerp} which uses FMA internally.
 */
public class Interpolator3 {
    private final double _000, _100, _010, _110, _001, _101, _011, _111;

    public Interpolator3(double _000, double _100,
                         double _010, double _110,
                         double _001, double _101,
                         double _011, double _111) {
        this._000 = _000;
        this._100 = _100;
        this._010 = _010;
        this._110 = _110;
        this._001 = _001;
        this._101 = _101;
        this._011 = _011;
        this._111 = _111;
    }

    public double trilerp(double x, double y, double z) {
        return InterpolationFunctions.triLerp(_000, _100, _010, _110, _001, _101, _011, _111, x, y, z);
    }
}