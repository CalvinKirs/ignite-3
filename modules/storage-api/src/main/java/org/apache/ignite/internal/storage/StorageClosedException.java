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

package org.apache.ignite.internal.storage;

import org.apache.ignite.lang.ErrorGroups.Storage;

/**
 * Exception that will be thrown when the storage is closed.
 */
public class StorageClosedException extends StorageException {
    private static final long serialVersionUID = -7988332521347221109L;

    /**
     * Default constructor.
     */
    public StorageClosedException() {
        this("Storage is already closed");
    }

    /**
     * Constructor.
     *
     * @param message Error message.
     */
    public StorageClosedException(String message) {
        super(Storage.ALREADY_CLOSED_ERR, message);
    }
}
