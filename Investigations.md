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

It seems something broke in the Terra build, when generating a ew world I am seeing many structure errors:

If reference needed: dead_entires_review.txt

At startup, it appears Terra is reloading twice, but before commit XXX this did not occur:

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

