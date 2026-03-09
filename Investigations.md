When I tried to run the profiler with the updates, it appears to have caused the server to crash.  Main log at C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\logs\latest_crash.log

Interesting, the pack loaded without an error, but when I tried to generate chunks the server crashed, please see logs at "C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\logs\latest.log"

Can you help me understand why the feature definition in "C:\Projects\ORIGEN2\features\world_features\river_soulsand.yml" does not appear to be placing the block "soul_sand" at the bottom of rivers in the CHIMERA world pack?  (Where a solid block is below water).  Are water source blocks defined by the ocean level added after feature placement?  Or is the range definition here incorrect in some way?

#####################################

I have attempted to change the locator pattern to check for adjacent air above a solid block but it does not appear to be working.  Can you help me understand what might still be wrong with the configuration?  Right now I'm just interested in placing soul-sand at 

################################

I want to investigate a method for Terra, a Terra add-on, or a separate minecraft plugin to trigger a block update on specified block positions after chunk generation is complete.

The advantage of using Terra or a Terra add-on is that it can be given a method to queue the update for a position after a chunk completes creation.

The advantage of a separate minecraft plugin is less complexity interacting with the current Terra architecture, but it would need it's own methods to know which block to update.  In this case just triggering a block update on any soul_sand type block that is below a source water block, in order to trigger bubble column generation.

############################

World generation is now failing but it is not clear to me why, I have tried older versions of Terra, Origen Pack, and removing the new plugin.

Reference logs from C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\logs\:
dead_entires_review.txt
stuck_on_chunk_gen_from_server_console.txt

A few notable items:
1. At startup, it appears Terra is reloading twice, but this did not occur from commit f864d79.

2. I am seeing multiple notes around dead entries, I have not seen these before: "Dead entry in 'com.dfsek.terra.api.structure.Structure' registry"

3. The first chunk never appears to complete, it is not clear to me what is holding the process.  Ex "NewChunkHolder{world=CHIMERA, chunkX=10, chunkZ=10, entityChunkFromDisk=false"

[09:39:30 INFO]: Loading config pack "TARTARUS:TARTARUS"
[09:39:31 INFO]: Loading config pack "REIMAGEND:REIMAGEND"
[09:39:31 INFO]: Loaded config pack "TARTARUS:TARTARUS" v1.0.0 by Jaddot in 1672.6169ms.
[09:39:31 INFO]: Loaded config pack "REIMAGEND:REIMAGEND" v3.0.0 by Aureus, RogueShade in 2001.5519ms.
[09:39:31 INFO]: DendryTerra: Registered DENDRY sampler type
[09:39:31 INFO]: Loading config pack "OVERWORLD:OVERWORLD"
[09:39:38 INFO]: Loaded config pack "OVERWORLD:OVERWORLD" v2.0.0 by Astrash, Sancires, Aureus, RogueShade in 8866.5549ms.
[09:39:46 INFO]: Loading config pack "CHIMERA:CHIMERA"
[09:39:53 INFO]: Loaded config pack "CHIMERA:CHIMERA" v2.0.1 by Astrash, Sancires, Aureus, Rearth, Belikhun, RogueShade, DIYTechy in 23729.6125ms.
[09:39:55 INFO]: Loading meta config packs...
[09:39:55 INFO]: Loading metapack "DEFAULT:DEFAULT"
[09:39:55 INFO]: Linked config pack "OVERWORLD:OVERWORLD" to metapack "DEFAULT:DEFAULT".
[09:39:55 INFO]: Linked config pack "TARTARUS:TARTARUS" to metapack "DEFAULT:DEFAULT".
[09:39:55 INFO]: Linked config pack "REIMAGEND:REIMAGEND" to metapack "DEFAULT:DEFAULT".
[09:39:55 INFO]: Loaded metapack "DEFAULT:DEFAULT" v1.0.0 by Jaddot, RogueShade, Aureus, Astrash, Sancires, Duplex in 38.1715ms.
[09:39:55 INFO]: Hacking biome registry...

#########################

Testing with the OVERWORLD, it appears to show the same error, but it finally creates the world at 67 s.  I assume this is because minecraft is trying to position strongholds in the world at instantiation, but it needs to run the biome processor to find valid placements in the world.  The issue is likely that the biome pipeline for Chimera takes much longer to run.  Does the Terra profiler contain details around the biome placement pipeline?  I'm curious if there is some timing profile on named samplers or a per stage file timing breakdown to see if there is a specific stage in the biome pipeline that is consuming a significant amount of time.

##################################3

I suspect now that the strongholds are just failing to place,  perhaps due to the number of extrusions or biome types of those extrusions, resulting in much larger query pattern.  Is there a way to also add into the profiler the number of biome queries since the start of the profiler?  This would make it clear if there are substantially more queries for CHIMERA which would imply increased stronghold placement failure rates.

#####################################

            LOGGER.error(" at {}", arguments.getOrigin(), e);

In this source file is where the error "Failed to generate structure" occurs.  I am seeing in the log this error with the context "NullPointerException", is there any more detail that can be added to this error message to indicate the origin of the failure mode?

################################3

One concerns is that the biome provider itself (before feature / structure placement itself) is consuming a significant amount of processing time.  In the terra profiler, I do not see many debugs related to the biome provider pipeline itself.  Are there details there that I'm missing?

###############################

I am seeing two categories to large performance hits, the first is related to large replace lists:

USE_RIVER - uses 27%, 155 mS per chunk (first stage in C:\Projects\ORIGEN2\biome-distribution\stages\add_rivers.yml)
ice_cap - uses 5%, 30 mS per chunk (first stage in C:\Projects\ORIGEN2\biome-distribution\stages\set_biomes_in_climates_origen.yml)

Is it possible the sampler is being re-evaluated for each replace stage even if it is not applicable to the current biome?  Or is it re-evaluated for each y-stage?  Or is the y-stage only used for extrusions?

Or is the cache sampler needing a larger number of samples due to multiple threads overwriting the other thread's value?

#################################33

Is there a way to confirm how queries are intercepted by Terra from Minecraft?  Is it just a chunk coordinate that it converts to world coordinates?  Does it center that request of world coordinates on the 112 x 112 grid?

####################################33

So, the rationale makes sense to me now, we need border information for some types of stages and smoothing cycles (if applicable), but the challenge here then is a substantial re-evaluation of samplers within stages that use the same sampler.  It just keeps redoing the same calculation multiple times.

I see some options: Increase the cache size for all 2d samplers in an attempt to mitigate re-evaluation of complex samplers at the cost of memory (and another challenge here is that the cache sampler has a unique x/z per minecraft chunk, further limiting their usefulness in a sparse lookup like biome placement)

Currently there are about 30 cached sampler types, although perhaps this is not necessary at such a substantial degreed.

I think this should be attacked from multiple angles:

Step 1. Plan improvements to the 2d cache:
A. The cache will now actually carry 2 cache tables, 1 constant chunk cache array of a strict 16x16 size (Dedicated for chunk generation, may be dead for many samplers), and the configurable sized array that exists today.
B. The 2d cache size default size will be 64 x 64 (exp = 7)
C. A cache sampler should be able to be configured with a right side bit shift.  This is expounded further below.
D. There would no longer be a need to store a hash / seed, each cache sampler is per thread and per sampler, the seed does not change, I assume a reload of Terra would cause all these caches to get reconstructed anyways.  All that is needed are x and z coordinates (double or int32 if int type), and the outcome value (double).
E. Update the "C:\Projects\ORIGEN2\OptomizePackSamplers.py" script to:
    i. Also default all cache samplers (remove their size) and set them to an integer type if the samplers used in their expressions are all direct x/z values (nothing is scaling them)
    ii. Add a "QueryRez" parameter that increases to 4 if the sampler is only used for biome placement (the sampler or other samplers that depend on it are ONLY used in biome placement, they do not appear as named samplers in any biome / extrusion files)
    iii. Upon receiving a coordinate pair, the sampler would first check to see if it exists in the sparse cache intended for biome pipeline determination.

Point 1B - A true 64x64 grid, exp=12.  I misspoke.
Point 2A - It can just store the coordinates for each point similar to how the definable cache works today.  If the point locations match, then the cache can be used, otherwise, it would recalculated.  Basically the same path as the configurable cache.  Then the "life" of the sampler is effectively continuous.
Point 3C - Correct
Point 4A - This brings up an interesting point, the high resolution cache technically is holding duplicate points, this could be designed so that the higher resolution cache is only storing the non-sparse lookups, but the lookup coordinates might need to be transformed more to store in the array such that there are no duplicates between the sparse cache and the high resolution cache.
Point E - Since this is getting more complex, lets come back to the python script after this.

In fact, ideally:
    The sparse cache is a 64 x 64 grid, storing each x/z coordinate for each coordinate and returned value.
    The high resolution cache default is 32 x 32 (instead of 16x16), storing each x/z coordinate for each coordinate and returned value.  If int is set, ideally the array x/z coordinates resolve to an array position that ensurers uniqueness from the sparse array.
    The size for each is configurable.  Like "exp" and "sparse-exp"
    The int flag is true by default (so coordinate storage space is less)



    iiii. Again, make sure to count each instance of sampler use in biome files.


Step 2. Plan improvements to the query range definition.  Set the minimum to 64 instead of 129, and produce warning if the calculated array size exceeds the this minimum.

Step 3. Pl

16x4 = 64 + 16 (border) = 80.

80 x 80 x 8 (x long) x 8 (z long) x 8 (result) = 3 mb per 2d cache. (20% loss assuming perfect grid generation)

Or 

80 x 80 x 8 (x long) x 4 (z long) x 8 (result) = 820 kB per 2d cache. (20% loss assuming perfect grid generation) for int type.

