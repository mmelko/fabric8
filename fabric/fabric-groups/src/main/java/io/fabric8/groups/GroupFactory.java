/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.groups;

import java.util.concurrent.ThreadFactory;

public interface GroupFactory {

    <T extends NodeState> Group<T> createGroup(String path, Class<T> clazz);

    <T extends NodeState> Group<T> createGroup(String path, Class<T> clazz, ThreadFactory threadFactory);

    <T extends NodeState> Group<T> createMultiGroup(String path, Class<T> clazz);

    <T extends NodeState> Group<T> createMultiGroup(String path, Class<T> clazz, ThreadFactory threadFactory);

}
