/**
 * Supported BuildCraft Community Edition extension API.
 *
 * <p>Addon compatibility guarantees apply to this namespace subject to the API version policy.
 * BuildCraft implementation packages such as {@code buildcraft.lib}, {@code buildcraft.transport}
 * and {@code buildcraft.factory} are not part of this contract.
 *
 * <h2>Threading and lifetime</h2>
 * <p>Unless a specific contract states otherwise, access to live game state (including lookups,
 * simulation and mutation) belongs to the owning logical game thread: server for server worlds,
 * client for client state. A simulated operation is not an asynchronous operation. Client
 * presentation/render callbacks use the client/render thread as required by Minecraft.
 * Registration belongs to loader-controlled initialization before {@code FROZEN}; addons must
 * finish registration work before the loader barrier and must not race lifecycle transitions.
 * Reload callbacks run in the invoking reload phase; do not assume a game-thread context or
 * touch live world state without scheduling onto its owner. Service and registry discovery,
 * immutable value objects and documented snapshots can be read off-thread. A reference returned
 * by discovery is not a thread-safety or lifetime guarantee for the underlying object.
 * Callbacks run on the caller's domain thread unless explicitly specified; they must not block
 * waiting for work on that same thread. See docs/api-v2-runtime-contracts.md for details.
 */
package buildcraft.api.v2;
