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