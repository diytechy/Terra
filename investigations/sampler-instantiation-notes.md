# Terra Sampler Instantiation Behavior

## How Terra Creates Sampler Instances from YAML

Every YAML sampler definition produces a **new, independent sampler instance**. The
config loading chain in `GenericTemplateSupplierLoader.load()` works as follows:

1. Looks up `type` in the registry -> gets a `Supplier<ObjectTemplate<Sampler>>`
2. Calls `.get()` on the supplier -> creates a **new** ObjectTemplate
3. Loads the YAML values into that template
4. Calls `.get()` on the template -> creates a **new** Sampler instance

This runs independently at every location in the config tree where a sampler appears.
Sampler objects are stateless noise functions (seed + coordinates -> value) with their
parameters baked in at construction time. They do not track callers or configurations.


## Instance Sharing Summary

| Scenario                                                    | Shared? |
|-------------------------------------------------------------|---------|
| Same `type:` block defined inline in 2 different YAML files | No      |
| YAML anchor `*name` referencing the same definition         | No      |
| Pack-level sampler referenced by name in expressions        | **Yes** |


## Details

### Inline definitions (separate instances)

When two YAML files (e.g., two biome configs) both define:
```yaml
type: OPEN_SIMPLEX_2
frequency: 0.01
```
Each goes through `GenericTemplateSupplierLoader.load()` independently, producing two
separate `OpenSimplex2Sampler` objects.

### YAML anchors (separate instances)

Even when using `&name` / `*name` anchors, the YAML parser substitutes the map values
inline, and the config loader runs the full create-template-load-instantiate chain at
each reference site. Two separate Sampler instances are created.

### Pack-level named samplers (shared instance)

Samplers defined in the pack's `samplers:` section are loaded **once** into the
`packSamplers` map (`NoiseAddon.java:157-166`). When multiple `EXPRESSION` samplers
reference the same pack sampler by name, `FunctionUtil.convertFunctionsAndSamplers()`
wraps the **same** underlying `Sampler` object in `NoiseFunction2`/`NoiseFunction3`
wrappers. All expressions share the same instance.

### Correctness implications

Since samplers are pure functions with no mutable state, sharing or not sharing
instances has no correctness impact. The only implications are for memory usage
and caching behavior (e.g., if wrapped in a `CacheSampler`, separate instances have
separate caches).


## Key Source Files

- `GenericTemplateSupplierLoader.java` - Config loader that creates new template + sampler per YAML definition
- `NoiseAddon.java:157-166` - Pack-level sampler loading (single instance stored in map)
- `ExpressionFunctionTemplate.java:55-66` - Merges global (pack) and local samplers for expressions
- `FunctionUtil.java:27-34` - Wraps sampler instances as paralithic expression functions
- `DimensionApplicableSampler.java` - Wrapper holding a Sampler + its dimension count
