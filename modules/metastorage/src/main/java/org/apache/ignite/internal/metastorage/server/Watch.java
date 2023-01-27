/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.metastorage.server;

import java.util.function.Predicate;
import org.apache.ignite.internal.metastorage.WatchEvent;
import org.apache.ignite.internal.metastorage.WatchListener;

/**
 * Subscription on updates of Meta Storage entries corresponding to a subset of keys, starting from a given revision number.
 */
public class Watch {
    /** Current revision. */
    private volatile long targetRevision;

    /** Key predicate. */
    private final Predicate<byte[]> predicate;

    /** Event listener. */
    private final WatchListener listener;

    /**
     * Constructor.
     *
     * @param startRevision Starting revision.
     * @param listener Event listener.
     * @param predicate Key predicate.
     */
    public Watch(long startRevision, WatchListener listener, Predicate<byte[]> predicate) {
        this.predicate = predicate;
        this.listener = listener;
        this.targetRevision = startRevision;
    }

    /**
     * Returns {@code true} if a given key and its revision should be forwarded to the event listener.
     *
     * @param key Meta Storage key.
     * @param revision Revision corresponding to the given {@code key}.
     */
    public boolean matches(byte[] key, long revision) {
        return revision >= targetRevision && predicate.test(key);
    }

    /**
     * Notifies the event listener about a Meta Storage event.
     */
    public void onUpdate(WatchEvent event) {
        listener.onUpdate(event);

        targetRevision = event.revision() + 1;
    }

    /**
     * Callback that gets called if an error has occurred during the event processing.
     */
    public void onError(Throwable e) {
        listener.onError(e);
    }

    /**
     * Returns the event listener.
     */
    public WatchListener listener() {
        return listener;
    }

    /**
     * Returns the current Meta Storage revision this Watch is listening to.
     */
    public long targetRevision() {
        return targetRevision;
    }
}