GTCEu EnergyNet Hotfix

Server-side performance hotpatch for GregTechCEu (GTCEu) EnergyNet on Minecraft Forge 1.20.1.

This project demonstrates a correctness-preserving optimization that significantly reduces TPS usage on large energy networks by eliminating structural inefficiencies in the current EnergyNet implementation.

Background

On large servers, GTCEu energy hatches and cables can consume a disproportionate amount of server tick time. Profiling with Spark shows that the bottleneck is not energy logic itself, but repeated structural work:

Global route-cache invalidation on every neighbor update

Repeated full network walks (PipeNetWalker.walk)

Repeated per-machine, per-tick endpoint capability resolution

Redundant destination probing across the same energy net

This causes poor scalability as machine count increases.

What this hotpatch does

This project applies a set of server-side mixin optimizations:

Local route-cache invalidation
Replaces global NET_DATA.clear() with targeted invalidation of only the affected pipe position and its neighbors.

Per-net, multi-tick endpoint handler caching
Caches resolved IEnergyContainer handlers per EnergyNet, avoiding repeated capability lookups every tick.

Per-tick sink state caching
Ensures multiple producers do not repeatedly probe the same destinations within a tick.

All changes are:

correctness-preserving

rebuild-safe (EnergyNet instances are replaced on rebuild)

server-side only

transparent to gameplay and machine behavior

Results

Measured on a production modpack with ~100 connected machines:

Energy hatch TPS usage reduced from ~40% → ~16%

PipeNetWalker.walk largely eliminated from steady-state ticking

Remaining cost dominated by actual energy transfer, not avoidable overhead

Purpose of this repository

This repository is not intended as a permanent drop-in mod.

It exists to:

document real-world EnergyNet performance issues

demonstrate a working optimization strategy

serve as reference code for an upstream EnergyNet redesign in GTCEu

GTCEu maintainers are encouraged to adapt or reimplement these ideas directly in the mod.

Compatibility

Minecraft Forge 1.20.1

GTCEu 7.4.0

Server-side only
