package com.dfsek.terra.addons.noise.config.sampler;

import com.dfsek.paralithic.eval.parser.Parser.ParseOptions;
import com.dfsek.paralithic.eval.tokenizer.ParseException;
import com.dfsek.paralithic.sampler.noise.ExpressionNoiseFunction;
import com.dfsek.paralithic.sampler.normalizer.ExpressionNormalizer;
import com.dfsek.seismic.type.sampler.Sampler;

import java.util.HashMap;
import java.util.Map;

import com.dfsek.terra.addons.noise.config.DimensionApplicableSampler;
import com.dfsek.terra.addons.noise.config.templates.FunctionTemplate;

import static com.dfsek.terra.addons.noise.paralithic.FunctionUtil.convertFunctionsAndSamplers;


/**
 * A sampler that defers expression compilation until first evaluation.
 * <p>
 * During pack loading, EXPRESSION samplers may reference other pack-level samplers
 * that haven't been compiled yet. This wrapper captures the expression configuration
 * and compiles it lazily on the first {@code getSample()} call, by which time all
 * pack samplers are available in the global registry.
 * <p>
 * Local samplers (defined inline in the expression config) always take priority
 * over global pack samplers with the same name.
 * <p>
 * Supports both plain expressions and expression normalizers (which wrap an input sampler).
 */
public class DeferredExpressionSampler implements Sampler {

    private final Map<String, DimensionApplicableSampler> globalSamplers;
    private final Map<String, FunctionTemplate> globalFunctions;
    private final Map<String, DimensionApplicableSampler> localSamplers;
    private final Map<String, FunctionTemplate> localFunctions;
    private final String expression;
    private final Map<String, Double> vars;
    private final ParseOptions parseOptions;
    private final Sampler normalizerInput;

    private volatile Sampler compiled;

    public DeferredExpressionSampler(Map<String, DimensionApplicableSampler> globalSamplers,
                                     Map<String, FunctionTemplate> globalFunctions,
                                     Map<String, DimensionApplicableSampler> localSamplers,
                                     Map<String, FunctionTemplate> localFunctions,
                                     String expression,
                                     Map<String, Double> vars,
                                     ParseOptions parseOptions) {
        this(globalSamplers, globalFunctions, localSamplers, localFunctions, expression, vars, parseOptions, null);
    }

    public DeferredExpressionSampler(Map<String, DimensionApplicableSampler> globalSamplers,
                                     Map<String, FunctionTemplate> globalFunctions,
                                     Map<String, DimensionApplicableSampler> localSamplers,
                                     Map<String, FunctionTemplate> localFunctions,
                                     String expression,
                                     Map<String, Double> vars,
                                     ParseOptions parseOptions,
                                     Sampler normalizerInput) {
        this.globalSamplers = globalSamplers;
        this.globalFunctions = globalFunctions;
        this.localSamplers = localSamplers;
        this.localFunctions = localFunctions;
        this.expression = expression;
        this.vars = vars;
        this.parseOptions = parseOptions;
        this.normalizerInput = normalizerInput;
    }

    @Override
    public double getSample(long seed, double x, double y) {
        return compile().getSample(seed, x, y);
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        return compile().getSample(seed, x, y, z);
    }

    /**
     * Eagerly compile the expression, surfacing any parse errors at pack load time
     * rather than deferring them to first evaluation.
     */
    public void validate() {
        compile();
    }

    /**
     * Get the original expression string (before compilation).
     * Used by auto-caching analysis to scan for pack sampler references.
     */
    public String getExpressionString() {
        return expression;
    }

    private Sampler compile() {
        Sampler s = compiled;
        if(s != null) return s;
        synchronized(this) {
            s = compiled;
            if(s != null) return s;
            var mergedFunctions = new HashMap<>(globalFunctions);
            mergedFunctions.putAll(localFunctions);
            var mergedSamplers = new HashMap<>(globalSamplers);
            mergedSamplers.putAll(localSamplers);
            try {
                var functionMap = convertFunctionsAndSamplers(mergedFunctions, mergedSamplers);
                if(normalizerInput != null) {
                    compiled = new ExpressionNormalizer(normalizerInput, functionMap, expression, vars, parseOptions);
                } else {
                    compiled = new ExpressionNoiseFunction(functionMap, expression, vars, parseOptions);
                }
                return compiled;
            } catch(ParseException e) {
                throw new RuntimeException("Failed to parse deferred expression.", e);
            }
        }
    }
}
