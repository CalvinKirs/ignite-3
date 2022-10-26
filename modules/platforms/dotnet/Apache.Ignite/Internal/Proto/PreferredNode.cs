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

namespace Apache.Ignite.Internal.Proto;

/// <summary>
/// Preferred node qualifier.
/// </summary>
internal record struct PreferredNode
{
    /// <summary>
    /// Gets the id.
    /// </summary>
    public string? Id { get; private init; }

    /// <summary>
    /// Gets the name.
    /// </summary>
    public string? Name { get; private init; }

    /// <summary>
    /// Creates an instance from name.
    /// </summary>
    /// <param name="name">Name.</param>
    /// <returns>Preferred node.</returns>
    public static PreferredNode FromName(string name) => new() { Id = null, Name = name };

    /// <summary>
    /// Creates an instance from id.
    /// </summary>
    /// <param name="id">Id.</param>
    /// <returns>Preferred node.</returns>
    public static PreferredNode FromId(string id) => new() { Id = id, Name = null };
}