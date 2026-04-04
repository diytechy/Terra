/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.noise.config.templates.noise;

import com.dfsek.paralithic.eval.parser.Parser.ParseOptions;

import com.dfsek.paralithic.sampler.noise.ExpressionNoiseFunction;
import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dfsek.tectonic.api.exception.ValidationException;

import com.dfsek.terra.addons.noise.config.DimensionApplicableSampler;
import com.dfsek.terra.addons.noise.config.sampler.DeferredExpressionSampler;
import com.dfsek.terra.addons.noise.config.templates.FunctionTemplate;
import com.dfsek.terra.addons.noise.config.templates.SamplerTemplate;

import com.dfsek.terra.api.config.meta.Meta;


@SuppressWarnings({ "FieldMayBeFinal", "unused" })
public class ExpressionFunctionTemplate extends SamplerTemplate<ExpressionNoiseFunction> {
    private final Map<String, DimensionApplicableSampler> globalSamplers;
    private final Map<String, FunctionTemplate> globalFunctions;
    private final ParseOptions parseOptions;
    private final AtomicBoolean deferCompilation;
    @Value("variables")
    @Default
    private @Meta Map<String, @Meta Double> vars = new HashMap<>();
    @Value("expression")
    private @Meta String expression;
    @Value("samplers")
    @Default
    private @Meta LinkedHashMap<String, @Meta DimensionApplicableSampler> samplers = new LinkedHashMap<>();
    @Value("functions")
    @Default
    private @Meta LinkedHashMap<String, @Meta FunctionTemplate> functions = new LinkedHashMap<>();

    public ExpressionFunctionTemplate(Map<String, DimensionApplicableSampler> globalSamplers,
                                      Map<String, FunctionTemplate> globalFunctions,
                                      ParseOptions parseOptions,
                                      AtomicBoolean deferCompilation) {
        this.globalSamplers = globalSamplers;
        this.globalFunctions = globalFunctions;
        this.parseOptions = parseOptions;
        this.deferCompilation = deferCompilation;
    }

    @Override
    public boolean validate() throws ValidationException {
        boolean result = super.validate();
        // Only warn if variables map is completely empty — this catches YAML merge failures.
        // If vars has any content, we trust that the later DeferredExpressionSampler compilation
        // will catch any actual undefined variable errors with full parse context.
        if(vars.isEmpty() && !expression.isBlank()) {
            throw new ValidationException(
                "Expression has no variables defined. Check that 'variables:' uses the correct YAML merge path:\n"
                + "  Expression: " + expression.strip().lines().findFirst().orElse("(empty)") + "\n"
                + "  variables: block is empty");
        }
        return result;
    }

    @Override
    public Sampler get() {
        if(deferCompilation.get()) {
            return new DeferredExpressionSampler(globalSamplers, globalFunctions, samplers, functions, expression, vars,
                parseOptions);
        }
        // Always use DeferredExpressionSampler so the expression string is retained
        // for auto-caching reference analysis. Validate immediately to surface parse errors.
        DeferredExpressionSampler deferred = new DeferredExpressionSampler(globalSamplers, globalFunctions, samplers,
            functions, expression, vars, parseOptions);
        deferred.validate();
        return deferred;
    }
}
