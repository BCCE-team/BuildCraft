/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net.cache;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.net.BCNetwork;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Provides a way of defining a cache of *some object* that will be sent from server to every client (when they are
 * needed). Each object has a specific integer ID.
 * <p>
 * This class is NOT thread safe -- the client view may ONLY be used on the client thread, and the server view may ONLY
 * be used the server thread.
 * <p>
 * Note that all custom instances should be added to {@link BuildCraftObjectCaches#registerCache(NetworkedObjectCache)},
 * in order to work properly */
public abstract class NetworkedObjectCache<T> {

    /**
     * A client normally requests a freshly announced ID within a few ticks. Keeping a large bounded window prevents
     * dynamic-NBT items from growing the server heap forever while still leaving ample time for those requests.
     */
    private static final int MAX_SERVER_ENTRIES = 65_536;
    private static final int MAX_CLIENT_ENTRIES = 65_536;
    private static final int MAX_REQUEST_BATCHES_PER_TICK = 4;

    static final boolean DEBUG_LOG = BCDebugging.shouldDebugLog("lib.net.cache");
    static final boolean DEBUG_CPLX = BCDebugging.shouldDebugComplex("lib.net.cache");

    /* Implementation notes -- this currently is a simple, never expiring object<->id cache. Because it doesn't ever
     * clear objects out of the cache we can guarantee that the index of an object is unique, just by incrementing a
     * single variable. */

    /** The default object -- used at the client in case the object hasn't been sent to the client yet. */
    protected final T defaultObject;

    private final Int2ObjectMap<T> serverIdToObject = new Int2ObjectOpenHashMap<>();
    /** Server side map of the object to its integer ID. Inverse of {@link #serverIdToObject} */
    private final Object2IntMap<T> serverObjectToId = createObject2IntMap();

    /** The ID for the next stored object. IDs are never reused, even after an entry is evicted. */
    private int serverCurrentId = 0;
    private final Queue<Integer> serverInsertionOrder = new LinkedList<>();

    /** The list of cached client-side objects. */
    private final Int2ObjectMap<Link> clientObjects = new Int2ObjectOpenHashMap<>();
    /** The list of all links that are currently unknown. */
    private final Queue<Link> clientUnknowns = new LinkedList<>();
    /** Insertion order for bounded eviction of resolved client cache entries. */
    private final Queue<Integer> clientInsertionOrder = new LinkedList<>();

    /** A server view of this cache. Contains methods specific to */
    private final ServerView serverView = new ServerView();
    private final ClientView clientView = new ClientView();

    public NetworkedObjectCache(T defaultObject) {
        this.defaultObject = defaultObject;
        serverObjectToId.defaultReturnValue(-1);
    }

    protected abstract Object2IntMap<T> createObject2IntMap();

    // Public API

    /** @return The server view of this cache. If the debug option "lib.net.cache" is enabled then this will check to
     *         make sure that this really is the server thread. */
    public ServerView server() {
        if (DEBUG_LOG) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (!server.isSameThread()) {
                throw new IllegalStateException("");
            }
        }
        return serverView;
    }

    /** @return The server view of this cache. If the debug option "lib.net.cache" is enabled then this will check to
     *         make sure that this really is the client thread. */
    public ClientView client() {
        if (DEBUG_LOG) {
            if (!NetworkedObjectCacheClientHandler.isSameThread()) {
                throw new IllegalStateException("");
            }
        }
        return clientView;
    }

    /** The server view of the cache. */
    public class ServerView {
        private ServerView() {}

        /** Stores the given object in this cache, returning its ID.
         *
         * @param value The object to store
         * @return The id that maps back to the canonicalised version of the value. */
        public int store(T value) {
            return serverStore(value);
        }

        /** Gets the ID for the given object, or -1 if this was not stored in the cache. {@link #store(Object)} is
         * preferred to this, as most uses (such as network sending) want the value to be stored and get a valid ID.
         *
         * @param value The value to get an id for
         * @return */
        public int getId(T value) {
            return serverGetId(value);
        }
    }

    /** The client view of the cache. */
    public class ClientView {
        private ClientView() {}

        /** @param id The id of the given object.
         * @return A link to the stored object. The returned link should be stored (only 1 instance exists per stored
         *         integer ID) in preference to calling this method, as then you can avoid the map lookup. Th returned
         *         link object is updated if */
        public Link retrieve(int id) {
            return clientRetrieve(id);
        }
    }

    /** Defines a link to a cached object (on the client - don't use this on the server). If */
    public class Link implements Supplier<T> {

        /** The stored, cached value. */
        T actual;

        /** The id of this value. */
        final int id;

        Link(int id) {
            this.id = id;
        }

        @Override
        public T get() {
            return actual == null ? defaultObject : actual;
        }

        public boolean hasBeenReceived() {
            return actual != null;
        }
    }

    // Abstract overridable methods

    /** Writes the specified object out to the buffer.
     *
     * @param obj The object to write.
     * @param buffer The buffer to write into. */
    protected abstract void writeObject(T obj, FriendlyByteBuf buffer);

    /** Reads the specified object from the buffer.
     *
     * @param buffer The buffer to read from
     * @return */
    protected abstract T readObject(FriendlyByteBuf buffer) throws IOException;

    /** @return The name of this cache to be used in debug messages. */
    protected String getCacheName() {
        return getClass().getSimpleName();
    }

    // Internal logic

    /** Stores the given object in this cache, returning its ID. SERVER SIDE.
     *
     * @param object
     * @return */
    private int serverStore(T object) {
        int current = serverObjectToId.getInt(object);
        if (current >= 0) {
            return current;
        }

        int id = serverCurrentId++;
        T copy = copyOf(object);
        serverObjectToId.put(copy, id);
        serverIdToObject.put(id, copy);
        serverInsertionOrder.add(id);
        evictOldServerEntries();

        if (DEBUG_CPLX) {
            String toString;
            if (copy instanceof FluidStack) {
                FluidStack fluid = (FluidStack) copy;
                toString = fluid.getTranslationKey();
            } else {
                toString = String.valueOf(copy);
            }
            BCLog.logger.info("[lib.net.cache] The cache " + getNameAndId() + " stored #" + id + " as " + toString);
        }
        return id;
    }

    private void evictOldServerEntries() {
        while (serverIdToObject.size() > MAX_SERVER_ENTRIES) {
            Integer oldestId = serverInsertionOrder.poll();
            if (oldestId == null) {
                return;
            }
            T removed = serverIdToObject.remove(oldestId.intValue());
            if (removed != null && serverObjectToId.getInt(removed) == oldestId.intValue()) {
                serverObjectToId.removeInt(removed);
            }
        }
    }

    protected abstract T copyOf(T object);

    /** Gets the ID for the given object, or -1 if this was not stored in the cache. SERVER SIDE.
     * {@link #serverStore(Object)} if preferred to this, as most uses (such as network sending) want the value to be
     * stored and get a valid ID.
     *
     * @param object
     * @return */
    private int serverGetId(T object) {
        return serverObjectToId.getInt(object);
    }

    /** Retrieves a link to the specified ID. CLIENT SIDE.
     *
     * @param id
     * @return */
    private Link clientRetrieve(int id) {
        if (id < 0) {
            return new Link(id);
        }

        Link current = clientObjects.get(id);
        if (current == null) {
            evictOldClientEntries();
            if (clientObjects.size() >= MAX_CLIENT_ENTRIES) {
                // All retained entries are still awaiting a response. Do not let a hostile or broken server grow
                // the client heap without bound; this uncached link safely resolves to the default object.
                return new Link(id);
            }

            if (DEBUG_CPLX) {
                BCLog.logger.info("[lib.net.cache] The cache " + getNameAndId() + " tried to retrieve #" + id
                    + " for the first time");
            }
            current = new Link(id);
            clientUnknowns.add(current);
            clientObjects.put(id, current);
            clientInsertionOrder.add(id);
        }
        return current;
    }

    private void evictOldClientEntries() {
        int attempts = clientInsertionOrder.size();
        while (clientObjects.size() >= MAX_CLIENT_ENTRIES && attempts-- > 0) {
            Integer oldestId = clientInsertionOrder.poll();
            if (oldestId == null) {
                return;
            }
            Link oldest = clientObjects.get(oldestId.intValue());
            if (oldest == null) {
                continue;
            }
            if (!oldest.hasBeenReceived()) {
                // Never evict an unresolved link: another game object may be holding that exact Link instance and
                // expects the response to update it in place. Rotate it to the back instead.
                clientInsertionOrder.add(oldestId);
                continue;
            }
            clientObjects.remove(oldestId.intValue());
        }
    }

    /** Used by {@link MessageObjectCacheRequest#HANDLER} to write the actual object out. */
    void writeObjectServer(int id, FriendlyByteBuf buffer) {
        T obj = serverIdToObject.get(id);
        if (obj == null) {
            // The ID fell outside the bounded retention window. Sending the default keeps the protocol valid; active
            // objects are announced again with a fresh ID when they are next synchronized.
            obj = defaultObject;
        }
        writeObject(obj, buffer);
    }

    /** Used by {@link MessageObjectCacheResponse#HANDLER} to read an object in.
     *
     * @param id
     * @param buffer
     * @throws IOException */
    void readObjectClient(int id, FriendlyByteBuf buffer) throws IOException {
        Link link = clientRetrieve(id);
        link.actual = readObject(buffer);
        if (DEBUG_CPLX) {
            T read = link.actual;
            String toString;
            if (read instanceof FluidStack) {
                FluidStack fluid = (FluidStack) read;
                toString = fluid.getTranslationKey();
            } else {
                toString = read.toString();
            }
            BCLog.logger
                .info("[lib.net.cache] The cache " + getNameAndId() + " just received #" + id + " as " + toString);
        }
    }

    final String getNameAndId() {
        return "(" + BuildCraftObjectCaches.CACHES.indexOf(this) + " = " + getCacheName() + ")";
    }

    void onClientWorldTick() {
        int batches = 0;
        while (!clientUnknowns.isEmpty() && batches++ < MAX_REQUEST_BATCHES_PER_TICK) {
            int count = Math.min(clientUnknowns.size(), MessageObjectCacheRequest.MAX_IDS);
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = clientUnknowns.remove().id;
            }
            if (DEBUG_CPLX) {
                BCLog.logger
                    .info("[lib.net.cache] The cache " + getNameAndId() + " requests ID's " + Arrays.toString(ids));
            }
            BCNetwork.sendToServer(new MessageObjectCacheRequest(this, ids));
        }
    }

    void onClientJoinServer() {
        clientObjects.clear();
        clientUnknowns.clear();
        clientInsertionOrder.clear();
    }
}
