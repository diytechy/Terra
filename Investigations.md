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

dead_entires_review.txt