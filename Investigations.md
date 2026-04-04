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

Plan improvements to the 2d cache:
A. The cache sampler has a new resolution parameter definition, array lookups are based on that resolution.  (So if resolution is 2, an x/z coordinate lookup of 0,0 and 1,1 would both attempt to occupy the same location in the cache array).  This way when the BiomeProvider is making queries on the resolution, it's spacing ensures it has unique hits on the cache array.  Queries for chunk generation can still overwrite the cache, but I do not think a single thread will be attempting chunk generation in parallel with biome lookups.  I am not sure if there is a way to confirm this.  This way the biome pipeline queries would have a full unique cache for it's resolution, but chunk generation would still benefit from single point hits.
B. The 2d cache size default size will be 64 x 64 (exp = 12)
C. There would no longer be a need to store a hash / seed, each cache sampler is per thread and per sampler.  The cache would initialize all values to NaN for doubles or int_max for integers, to ensure a 0,0 coordinate hit does result in recompute.

Make a plan to update the "C:\Projects\ORIGEN2\OptomizePackSamplers.py" script to:
    A. Count the number of sampler usages inside of Biomes (including aliasing usage), and write out a table of all named samplers and the number of times they are used through both biome pipeline stages and individual biome usages through their terrain.
    B. Have a parameter at the top of the script that indicates the number of sampler usages that are needed to convert it to a cache sampler.
    C. Allow it to edit existing cache samplers (not just convert uncached samplers to cached):
        i. Default size of all existing cache samplers (remove their size / exp setting), set them to an integer type, and set the new "resolution" definition to match the resolution of the pack biome sampler.
        i. Remove any cache sampler parents from any samplers that do not exceed the usage threshold.


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


Step 2. Plan improvements to the biome pipeline array creation / query range definition and the biome pipeline cache.  Set the maximum size to 64 instead of using a minimum definition size of 129, and increment "initialSize" right up until the array size is as large as possible without exceeding this maximum size.  If the initial chunk size returned from "BiomeChunkImpl.calculateChunkSize(arraySize, chunkOriginArrayIndex, expanderCount);" is greater than this maximum value, create a warning in the log but use that value.

Also allow for 4 of these caches to exist per thread instead of a single large one.  That way if the biome sampler keeps crossing the boundary edge of the Terra chunk, it does not need to continuously recompute back and forth between the two chunks, but can instead bounce between the two most recent caches. 

Finally, should this even be implemented as a caffeine cache?  Can't it just be a static persistent cache?

Step 3. Pl

16x4 = 64 + 16 (border) = 80.

80 x 80 x 8 (x long) x 8 (z long) x 8 (result) = 3 mb per 2d cache. (20% loss assuming perfect grid generation)

Or 

80 x 80 x 8 (x long) x 4 (z long) x 8 (result) = 820 kB per 2d cache. (20% loss assuming perfect grid generation) for int type.

I'm considering the fact that threads may jump between different regions, and in this case it would make sense for the biome pipeline cache to be available to all threads.  Is it possible for Terra to know how many threads are active?  Can you create a plan to modify the biome provider cache to be available to all threads, and initialize to a size of 4xthreadcount?

#############################################

I think there is a bug in the most recent cache updates.  Running the BiomeTool I am seeing some metrics that don't seem to align with what I would expect.

When the BiomeTool launches it starts with a default block area view of about 1300 x 600 blocks (780000 blocks total).  This is about 3000 minecraft chunks.

With the current build, the performance statistics are:
4 Threads
440 seconds to render
28107 samples at a 65x65 grid, at resolution of 2, this would cover about 112 x 112 blocks with border accommodations (~10000 blocks per Terra chunk)
I would have expected 78 samples to be evaluated at the best case.  It seems the new cache method is somehow resulting in either mismatches in the cache, or overwriting.

Now with the older Terra build f0b2706f4:
4 Threads
67 seconds to render
44 samples at what should be 129 x 129 grid. (about a 200 x 200 grid, so best case 20 samples)

#############################################

Why is the size noted as 65 x 65 when it should be capped at 64 x 64?

#########################################33

Now a new problem appears to be that the cache sampler does not appear to be offering any improvement, but at 64x64 entries default it should be able to cover the entire range of the biome pipeline and prevent sampler recompute, but I see no difference between in performance between with / without the cache sampler, even though some samplers are reused over 30 times.

Please investigate, knowing the configuration is at "C:\Projects\ORIGEN2".

##############################################333

Create a plan to change the way the Terra engine is handling sampler compilation.

For every sampler, when it's compiled it should be able to cache it's last value in terms of x, y (if dimensions =3), z, and seed.  This way when a sampler is recalled during chunk generation it can just provide it's cached value instead of recompute.  I see no reason this couldn't be done for every compiled sampler, as it would consume at most 20 bytes per compiled sampler.

In addition to this create a plan to make sure samplers can compile with named samplers that may have not been compiled yet or available.  This could be done via lazy-resolving sampler references with verification after all samplers are compiled, but it may be beneficial for the pack loading stage to first build a hierarchy of samplers, compile them in the order of needed usage (samplers with no dependencies first), and after each compile make sure that named sampler is available for the next compile process.  Then each sampler's usage count can be computed, which may be useful for future automated caching for the biome generation pipeline.

Actually caching of the last value should only be done for 2 dimensional samplers as it will likely be reused through the column, 3 dimensional samplers would rarely benefit from caching.

Please proceed with phase 1 and 2, but note automated caching of the last sampler value should only occur on 2-dimensional samplers, assuming that is trivial to check.

Now that we have a deferred expression method, I assume we can also compute the number of times a global pack sampler is used when it is tied back to the placeholder.  I want to plan a separate method of caching the entire array of that sampler across it's biome-provider array points (Similar to how the final output is cached), caching the sampler output in double.  This could also be a caffeine cache, but I assume each biome pipeline triggers on a separate thread.  This one probably could be a simpler thread local cache.  Ideally at stage determination the number of sampler usages could be derived via permutation usage in dependent samplers, and a threshold could be used to define how many iterations are needed to cache a sampler's output across the biome pipeline array.  Then the array would just be initialized to the size of the biome pipeline provider (per thread) * the number of samplers to cache.

This way samplers don't need to be explicitly defined in the pack file, and caching happens implicitly.  It also removes the need to store each coordinate in the cache sampler since the outcome is just based on 

I'd like to explore a method for the cache sampler to store data sample values in a unit definition type of single instead of double, and I would like to understand if there might be a way in the 2d sampler to set a 2d chunky

################################

I'm getting a new error with some changes from "":

[14:18:24] [Server thread/ERROR]: Encountered an unexpected exception
java.lang.RuntimeException: Chunk system crash propagated from unrecoverableChunkSystemFailure
	at ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.lambda$unrecoverableChunkSystemFailure$0(ChunkTaskScheduler.java:327) 
...
Caused by: java.lang.RuntimeException: Failed to parse deferred expression.
	at com.dfsek.terra.addons.noise.config.sampler.DeferredExpressionSampler.compile(DeferredExpressionSampler.java:101) ~[?:?]
...
Caused by: com.dfsek.paralithic.eval.tokenizer.ParseException: 2 errors occured. First:   1:12: Unknown variable: 'openingsDeltaShallow'
	at Terra-bukkit-7.0.0-BETA-e971aef4a-shaded.jar//com.dfsek.paralithic.eval.tokenizer.ParseException.create(ParseException.java:42) ~[?:?]

This is after modifying the file "C:\Projects\ORIGEN2\biomes\abstract\terrain\land\eq_global_river.yml".

A few points:
1. I would not have expected this sampler to be converted to a deferred sampler, or are all expressions now assumed to be deferred?
2. Getting errors when the sampler is first called is strange because a samplers use is not guaranteed, and may happen hours after they are built depending on other dependencies, can an init function call all named samplers at some coordinate set once after they are all loaded to make sure they are fully functional and trigger any errors instead of relying on chance if they are called later?

############################################

I want to plan for some updates to make Terra perform biome queries more quickly.

Currently, for any sampler to cache multiple samples it relies on pack samplers to have a cache type.

When samplers are instanced this can consume large chunks of memory usage that is never freed, as each coordinate is stored in the cache sampler.

I'd like make some updates to bypass the need for caching entirely and better automate the process of allocation of memory and determination of when memory should be freed.

Each time the biome query / biome pipeline runs a pack level named sampler, it should cache all those samples for the sampler in a thread specific cache that locates the sampler and it's Terra chunk coordinates that it was evaluated form.  This would be would be the same way fully evaluated biome sampler results are cached now, the only difference is that sampler values would also have their values cached if used during the biome query process.

This should mitigate the need for explicitly set "CACHE" samplers.

In addition, the sampler cache could be located by the Terra chunk coordinates, instead of storing each sample x/z value.

In order to determine priority of each cache and when / if it can be created, Terra should have a loose method to determine:

If a pack level named sampler is used in the biome pipeline.
How complex the sampler appears to be (expressions may be quite costly, compared to raw sampler values)
How many times a sampler is used within the biome pipeline generation process based on it's usage in other samplers that cumulate into the final output.
The complexity * number of uses should give a final weight, where higher values indicate the sampler uses a lot of cpu and would benefit substantially from caching within the biome pipeline.

Available memory in the parent process can then be used for Terra to derive what samplers should be cached when a chunk is processed for the biome pipeline.

This would largely eliminate the need for individually defined cache samplers, reduce the footprint of cached results (since they would be located by Terra chunk coordinates instead of individual x/z coordinates), and simplify memory usage and garbage collection, such that if the biome pipeline has not been queried for x time and it is complete, memory allocated for sampler cache creation can be freed up until needed again.






##################################

I'm trying to call a sampler with "NaN" inputs to trigger a special value, but it seems the Terra sampler compiler is attempting to resolve the label "NaN" to another name, or it is being corrupted on yml read.

Do I need to formulate the expression in the config pack differently (At C:\Projects\ORIGEN2\math\samplers\rivers.yml).  I also tried to create a variable name "nan_Input" setting it to "NaN", but  that also does not appear to work.

The error is:
java.lang.RuntimeException: Failed to compile 1 deferred expression sampler(s):
  maxRiverWidthAtLevel: 2 errors occured. First:   2:24: Unknown variable: 'NaN'
        at com.dfsek.terra.addons.noise.NoiseAddon.lambda$initialize$23(NoiseAddon.java:203)
        at com.dfsek.terra.event.EventContextImpl.lambda$handle$0(EventContextImpl.java:50)

##################################3

When Terra is loading it still appears to be showing content that I would like to instead only be shown when the profiler debug is enabled.  Commit ec49ca0c89a should have suppressed many of these loggers to only appear when the profiler was enabled, and I want to ensure remaining loggers are also gated behind the profiler enable.  Example of logger details I still see that I want to be gated:

[23:50:13 WARN]: Pipeline array size 81 exceeds maximum 64 due to stage border requirements
[23:50:13 INFO]: Pipeline sampler caching: selected 3 samplers (budget allows up to 19)
  Memory per sampler: 51 KB
  Total per thread: 153 KB
  Selected samplers (by weight):
    [0] spotDistance: complexity=108, uses=2, weight=216
    [1] spotRadius: complexity=50, uses=2, weight=100
    [2] spotSizePercent: complexity=1, uses=3, weight=3

##################

Please investigate the following error generated by Terra (C:\ProjectsTerra) and this world CHIMERA definition.  It seems like when this occured completely different biomes were constructed for the areas in question (where the user teleported), which I suspect means there is an issue in the biome pipeline at these points which is somehow not resulting in a crash but a fallback to some other biome selection.  I would actually prefer a crash in these cases, but I will note the BiomeTool appears to set the correct biomes, so I am unsure how execution in paper minecraft could be any different from the biomeTool unless there is some other timeout forcing a deviation in behavior.  It also seems there is considerable lag before new chunks start to generate, implying there is some process that is spinning up.  This was the first new chunk I teleported to after loading the server and generating chunks with the CHUNKY plugin, is there some interaction here with the CHUNKY plugin that could be causing a direct chunk load (not through chunky) to populate incorrect biome coordinates? The log segment (From the full log at "C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\logs\latest_no_volcano_where_it_should_be.log") that contains the error is:

[07:51:03 INFO]: [diy_techy: Teleported diy_techy to -699.500000, 112.892752, -4999.500000]
[07:51:12 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH  - 1.21.11-74-3ebc5b3 (MC: 1.21.11) ---
[07:51:12 ERROR]: The server has not responded for 10 seconds! Creating thread dump
[07:51:12 ERROR]: ------------------------------
[07:51:12 ERROR]: Server thread dump (Look for plugins here before reporting to Paper!):
[07:51:12 ERROR]: Chunk wait task info below: 
[07:51:13 ERROR]: Chunk wait: [( -44,-313) in 'CHIMERA']
[07:51:13 ERROR]: Chunk holder: NewChunkHolder{world=CHIMERA, chunkX=-44, chunkZ=-313, entityChunkFromDisk=false, lastChunkCompletion={chunk_class=net.minecraft.world.level.chunk.ProtoChunk,status=minecraft:carvers}, currentGenStatus=minecraft:carvers, requestedGenStatus=minecraft:full, generationTask=null, generationTaskStatus=null, priority=BLOCKING, priorityLocked=false, neighbourRequestedPriority=null, effective_priority=BLOCKING, oldTicketLevel=33, currentTicketLevel=33, totalNeighboursUsingThisChunk=6, fullNeighbourChunksLoadedBitset=0, currentChunkStatus=INACCESSIBLE, pendingChunkStatus=INACCESSIBLE, is_unload_safe=ticket_level, killed=false}
[07:51:13 ERROR]: ------------------------------
[07:51:13 ERROR]: Current Thread: Server thread
[07:51:13 ERROR]: 	PID: 49 | Suspended: false | Native: false | State: TIMED_WAITING
[07:51:13 ERROR]: 	Stack:
[07:51:13 ERROR]: 		java.base@23/jdk.internal.misc.Unsafe.park(Native Method)
[07:51:13 ERROR]: 		java.base@23/java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:269)
[07:51:13 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.waitForTasks(BlockableEventLoop.java:172)
[07:51:13 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.managedBlock(BlockableEventLoop.java:162)
[07:51:13 ERROR]: 		net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock(ServerChunkCache.java:795)
[07:51:13 ERROR]: 		net.minecraft.server.level.ServerChunkCache.syncLoad(ServerChunkCache.java:120)
[07:51:13 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunkFallback(ServerChunkCache.java:154)
[07:51:13 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunk(ServerChunkCache.java:317)
[07:51:13 ERROR]: 		net.minecraft.world.level.Level.getChunk(Level.java:968)
[07:51:13 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2202)
[07:51:13 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2183)
[07:51:13 ERROR]: 		net.minecraft.server.network.ServerGamePacketListenerImpl.handleAcceptTeleportPacket(ServerGamePacketListenerImpl.java:769)
[07:51:13 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:33)
[07:51:13 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:8)
[07:51:13 ERROR]: 		net.minecraft.network.PacketProcessor$ListenerAndPacket.handle(PacketProcessor.java:99)
[07:51:13 ERROR]: 		net.minecraft.network.PacketProcessor.executeSinglePacket(PacketProcessor.java:33)
[07:51:13 ERROR]: 		net.minecraft.server.MinecraftServer.runAllTasksAtTickStart(MinecraftServer.java:1187)
[07:51:13 ERROR]: 		net.minecraft.server.MinecraftServer.processPacketsAndTick(MinecraftServer.java:1673)
[07:51:13 ERROR]: 		net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1342)
[07:51:13 ERROR]: 		net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:388)
[07:51:13 ERROR]: 		net.minecraft.server.MinecraftServer$$Lambda/0x000001f33700e1c0.run(Unknown Source)
[07:51:13 ERROR]: 		java.base@23/java.lang.Thread.runWith(Thread.java:1588)
[07:51:13 ERROR]: 		java.base@23/java.lang.Thread.run(Thread.java:1575)
[07:51:13 ERROR]: ------------------------------
[07:51:13 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH ---
[07:51:13 ERROR]: ------------------------------
[07:51:17 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH  - 1.21.11-74-3ebc5b3 (MC: 1.21.11) ---
[07:51:17 ERROR]: The server has not responded for 15 seconds! Creating thread dump
[07:51:17 ERROR]: ------------------------------
[07:51:17 ERROR]: Server thread dump (Look for plugins here before reporting to Paper!):
[07:51:17 ERROR]: Chunk wait task info below: 
[07:51:17 ERROR]: Chunk wait: [( -44,-313) in 'CHIMERA']
[07:51:18 ERROR]: Chunk holder: NewChunkHolder{world=CHIMERA, chunkX=-44, chunkZ=-313, entityChunkFromDisk=false, lastChunkCompletion={chunk_class=net.minecraft.world.level.chunk.ProtoChunk,status=minecraft:carvers}, currentGenStatus=minecraft:carvers, requestedGenStatus=minecraft:full, generationTask=ChunkProgressionTask{class: ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkUpgradeGenericStatusTask, for world: CHIMERA, chunk: (-44,-313), hashcode: 1839319982, priority: COMPLETING, status: minecraft:features, scheduled: true}, generationTaskStatus=minecraft:features, priority=BLOCKING, priorityLocked=false, neighbourRequestedPriority=null, effective_priority=BLOCKING, oldTicketLevel=33, currentTicketLevel=33, totalNeighboursUsingThisChunk=1, fullNeighbourChunksLoadedBitset=0, currentChunkStatus=INACCESSIBLE, pendingChunkStatus=INACCESSIBLE, is_unload_safe=ticket_level, killed=false}
[07:51:18 ERROR]: ------------------------------
[07:51:18 ERROR]: Current Thread: Server thread
[07:51:18 ERROR]: 	PID: 49 | Suspended: false | Native: false | State: RUNNABLE
[07:51:18 ERROR]: 	Stack:
[07:51:18 ERROR]: 		java.base@23/java.lang.Thread.yield0(Native Method)
[07:51:18 ERROR]: 		java.base@23/java.lang.Thread.yield(Thread.java:450)
[07:51:18 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.waitForTasks(BlockableEventLoop.java:171)
[07:51:18 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.managedBlock(BlockableEventLoop.java:162)
[07:51:18 ERROR]: 		net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock(ServerChunkCache.java:795)
[07:51:18 ERROR]: 		net.minecraft.server.level.ServerChunkCache.syncLoad(ServerChunkCache.java:120)
[07:51:18 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunkFallback(ServerChunkCache.java:154)
[07:51:18 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunk(ServerChunkCache.java:317)
[07:51:18 ERROR]: 		net.minecraft.world.level.Level.getChunk(Level.java:968)
[07:51:18 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2202)
[07:51:18 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2183)
[07:51:18 ERROR]: 		net.minecraft.server.network.ServerGamePacketListenerImpl.handleAcceptTeleportPacket(ServerGamePacketListenerImpl.java:769)
[07:51:18 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:33)
[07:51:18 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:8)
[07:51:18 ERROR]: 		net.minecraft.network.PacketProcessor$ListenerAndPacket.handle(PacketProcessor.java:99)
[07:51:18 ERROR]: 		net.minecraft.network.PacketProcessor.executeSinglePacket(PacketProcessor.java:33)
[07:51:18 ERROR]: 		net.minecraft.server.MinecraftServer.runAllTasksAtTickStart(MinecraftServer.java:1187)
[07:51:18 ERROR]: 		net.minecraft.server.MinecraftServer.processPacketsAndTick(MinecraftServer.java:1673)
[07:51:18 ERROR]: 		net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1342)
[07:51:18 ERROR]: 		net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:388)
[07:51:18 ERROR]: 		net.minecraft.server.MinecraftServer$$Lambda/0x000001f33700e1c0.run(Unknown Source)
[07:51:18 ERROR]: 		java.base@23/java.lang.Thread.runWith(Thread.java:1588)
[07:51:18 ERROR]: 		java.base@23/java.lang.Thread.run(Thread.java:1575)
[07:51:18 ERROR]: ------------------------------
[07:51:18 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH ---
[07:51:18 ERROR]: ------------------------------
[07:51:22 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH  - 1.21.11-74-3ebc5b3 (MC: 1.21.11) ---
[07:51:22 ERROR]: The server has not responded for 20 seconds! Creating thread dump
[07:51:22 ERROR]: ------------------------------
[07:51:22 ERROR]: Server thread dump (Look for plugins here before reporting to Paper!):
[07:51:22 ERROR]: Chunk wait task info below: 
[07:51:22 ERROR]: Chunk wait: [( -44,-313) in 'CHIMERA']
[07:51:22 ERROR]: Chunk holder: NewChunkHolder{world=CHIMERA, chunkX=-44, chunkZ=-313, entityChunkFromDisk=false, lastChunkCompletion={chunk_class=net.minecraft.world.level.chunk.ProtoChunk,status=minecraft:initialize_light}, currentGenStatus=minecraft:initialize_light, requestedGenStatus=minecraft:full, generationTask=null, generationTaskStatus=null, priority=BLOCKING, priorityLocked=false, neighbourRequestedPriority=null, effective_priority=BLOCKING, oldTicketLevel=33, currentTicketLevel=33, totalNeighboursUsingThisChunk=10, fullNeighbourChunksLoadedBitset=0, currentChunkStatus=INACCESSIBLE, pendingChunkStatus=INACCESSIBLE, is_unload_safe=ticket_level, killed=false}
[07:51:22 ERROR]: ------------------------------
[07:51:22 ERROR]: Current Thread: Server thread
[07:51:22 ERROR]: 	PID: 49 | Suspended: false | Native: false | State: TIMED_WAITING
[07:51:22 ERROR]: 	Stack:
[07:51:22 ERROR]: 		java.base@23/jdk.internal.misc.Unsafe.park(Native Method)
[07:51:22 ERROR]: 		java.base@23/java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:269)
[07:51:22 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.waitForTasks(BlockableEventLoop.java:172)
[07:51:22 ERROR]: 		net.minecraft.util.thread.BlockableEventLoop.managedBlock(BlockableEventLoop.java:162)
[07:51:22 ERROR]: 		net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock(ServerChunkCache.java:795)
[07:51:22 ERROR]: 		net.minecraft.server.level.ServerChunkCache.syncLoad(ServerChunkCache.java:120)
[07:51:22 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunkFallback(ServerChunkCache.java:154)
[07:51:22 ERROR]: 		net.minecraft.server.level.ServerChunkCache.getChunk(ServerChunkCache.java:317)
[07:51:22 ERROR]: 		net.minecraft.world.level.Level.getChunk(Level.java:968)
[07:51:22 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2202)
[07:51:22 ERROR]: 		net.minecraft.world.entity.Entity.absSnapTo(Entity.java:2183)
[07:51:22 ERROR]: 		net.minecraft.server.network.ServerGamePacketListenerImpl.handleAcceptTeleportPacket(ServerGamePacketListenerImpl.java:769)
[07:51:22 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:33)
[07:51:22 ERROR]: 		net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket.handle(ServerboundAcceptTeleportationPacket.java:8)
[07:51:22 ERROR]: 		net.minecraft.network.PacketProcessor$ListenerAndPacket.handle(PacketProcessor.java:99)
[07:51:22 ERROR]: 		net.minecraft.network.PacketProcessor.executeSinglePacket(PacketProcessor.java:33)
[07:51:22 ERROR]: 		net.minecraft.server.MinecraftServer.runAllTasksAtTickStart(MinecraftServer.java:1187)
[07:51:22 ERROR]: 		net.minecraft.server.MinecraftServer.processPacketsAndTick(MinecraftServer.java:1673)
[07:51:22 ERROR]: 		net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1342)
[07:51:22 ERROR]: 		net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:388)
[07:51:22 ERROR]: 		net.minecraft.server.MinecraftServer$$Lambda/0x000001f33700e1c0.run(Unknown Source)
[07:51:22 ERROR]: 		java.base@23/java.lang.Thread.runWith(Thread.java:1588)
[07:51:22 ERROR]: 		java.base@23/java.lang.Thread.run(Thread.java:1575)
[07:51:22 ERROR]: ------------------------------
[07:51:22 ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH ---
[07:51:22 ERROR]: ------------------------------

---

## ANALYSIS: Biome Chunk Generation Hang + Wrong Biome Issue

### Problem Summary
Chunk generation at (-44, -313) in CHIMERA world hung for 20+ seconds while Paper waited for the chunk to complete generation (stuck at carvers → features → initialize_light stages). When it eventually completed (or timed out), the chunks had incorrect biomes despite BiomeTool showing the correct biomes. This occurred on the first new chunk teleported to after using CHUNKY for pre-generation.

### Root Cause Analysis

**Primary Issue: Single-Threaded Cache Executor Bottleneck**

1. **CachingBiomeProvider uses a single-threaded executor**
   - File: `common/api/src/main/java/com/dfsek/terra/api/util/cache/CacheUtils.java`
   - `CACHE_EXECUTOR = Executors.newSingleThreadExecutor()`
   - When CachingBiomeProvider wraps Terra's BiomeProvider, it caches 3D biome lookups with an async executor
   - Problem: Cache.get() BLOCKS until the value is computed. If multiple biome queries hit the cache at the same time (which they do during chunk generation), they all queue on the single CACHE_EXECUTOR thread
   - During chunk generation, Paper makes MANY biome queries (for terrain shaping, biome decoration, etc.). A single slow biome computation can back up the entire queue

2. **Pipeline Biome Generation Overhead**
   - File: `common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/PipelineBiomeProvider.java`
   - PipelineBiomeProvider caches BiomeChunks with max size of 256
   - BiomeChunk generation calls `pipeline::generateChunk()` which executes the entire biome pipeline
   - If pipeline computation is slow, and cache misses occur frequently, chunks can stall
   - CHUNKY pre-generation may have exhausted the 256-entry BiomeChunk cache, causing high cache miss rates for new chunks

3. **Potential Cache Interaction with CHUNKY**
   - CHUNKY generates chunks using vanilla Minecraft or its own generation, not Terra's biome provider
   - When a new chunk is generated adjacent to CHUNKY-generated chunks, Paper might:
     - Request biome info for boundary checking
     - Attempt biome blending at chunk boundaries
     - Load cached data that was generated with different biome logic
   - This could cause the biome provider to be called more frequently during generation of chunks near CHUNKY boundaries

**Secondary Issue: Vanilla ChunkGenerator Fallback**

NMSChunkGeneratorDelegate delegates terrain filling to `vanilla.fillFromNoise()`, which is the original Minecraft chunk generator. The vanilla generator uses its own BiomeSource (not NMSBiomeProvider). If there's any fallback path or timeout recovery, it might result in terrain being generated with vanilla biomes instead of Terra biomes.

### Why BiomeTool Shows Correct Biomes

BiomeTool likely directly calls the biome provider with specific coordinates, without going through the chunk generation pipeline. It wouldn't experience the same cache contention or timeout issues that the live server does.

### Why Generation Hangs For 10-20 Seconds

1. The server thread calls `getChunk()` which eventually calls `syncLoad()`
2. `syncLoad()` blocks the server thread waiting for the chunk generation CompletableFuture to complete
3. Chunk generation calls `getNoiseBiome()` many times (potentially 100+ times per chunk)
4. With CachingBiomeProvider, cache misses cause `Cache.get()` to block on the single CACHE_EXECUTOR
5. If the pipeline computation is slow, these get backed up, causing the server thread to wait

### Why BiomeTool Shows Different Biomes After Generation

When the chunk finally generates (after timeout or queue processing), it may have:
1. Used cached BiomeChunks from the cache (which might be stale or represent different seed states due to CHUNKY interference)
2. Fallen back to vanilla generator for some terrain blocks
3. Had biome boundaries that don't match what BiomeTool calculated due to pipeline state differences

### Recommendations

1. **Increase Cache Executor Threads**
   - Change CACHE_EXECUTOR from single-threaded to a small thread pool (e.g., 4 threads)
   - This would allow multiple biome queries to compute in parallel
   
2. **Monitor Biome Provider Query Count**
   - NMSBiomeProvider has `getBiomeQueryCount()` method (line 61 in NMSBiomeProvider.java)
   - Add logging to see how many biome queries occur during chunk generation
   - If it's very high, the pipeline might need caching optimization

3. **Increase BiomeChunk Cache Size**
   - PipelineBiomeProvider caches BiomeChunks with max size of 256 (line 49)
   - Consider increasing this if you have many biome chunks in active generation area
   - Monitor cache eviction rates

4. **Test Without CHUNKY Pre-generation**
   - Generate chunks directly in-game to see if the hang still occurs
   - If it doesn't, the issue is specific to CHUNKY interaction

5. **Add Debug Logging**
   - Log when Cache.get() blocks for extended periods
   - Log when BiomeChunk cache is evicted
   - Log biome query count and average time per query

6. **Consider Biome Provider Caching Bypass**
   - For chunk generation, consider using the uncached biome provider if available
   - Caching during chunk generation might cause more contention than benefit if queries are mostly unique