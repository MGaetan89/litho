/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.samples.litho.java.communicating

class ParentToChildEventController {
  private var observer: ((String) -> Unit)? = null

  fun setTriggerShowToastListener(observer: (String) -> Unit) {
    this.observer = observer
  }

  fun trigger(message: String) {
    observer?.invoke(message)
  }

  fun reset() {
    observer = null
  }
}
