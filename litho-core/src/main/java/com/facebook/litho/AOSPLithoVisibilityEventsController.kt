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

package com.facebook.litho

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.config.ComponentsConfiguration

/**
 * This LithoVisibilityEventsController implementation dispatches to the registered observers the
 * lifecycle state changes triggered by the provided LifecycleOwner. For example, if a Fragment is
 * passed as param, the observers will be registered to listen to all of the fragment's lifecycle
 * state changes.
 */
open class AOSPLithoVisibilityEventsController(final override val lifecycleOwner: LifecycleOwner) :
    LithoVisibilityEventsController, LifecycleEventObserver, AOSPLifecycleOwnerProvider {

  private val delegate: LithoVisibilityEventsControllerDelegate =
      if (ComponentsConfiguration.defaultInstance
          .enableInitStateForAOSPLithoVisibilityEventsController)
          LithoVisibilityEventsControllerDelegate(
              if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
                  LithoVisibilityState.HINT_VISIBLE
              else LithoVisibilityState.HINT_INVISIBLE)
      else {
        LithoVisibilityEventsControllerDelegate()
      }

  init {
    lifecycleOwner.lifecycle.addObserver(this)
  }

  override val visibilityState: LithoVisibilityState
    get() = delegate.visibilityState

  override fun moveToVisibilityState(newVisibilityState: LithoVisibilityState) {
    delegate.moveToVisibilityState(newVisibilityState)
  }

  override fun addListener(listener: LithoVisibilityEventsListener) {
    delegate.addListener(listener)
  }

  override fun removeListener(listener: LithoVisibilityEventsListener) {
    delegate.removeListener(listener)
  }

  override fun onStateChanged(source: LifecycleOwner, event: Event) {
    when (event) {
      Lifecycle.Event.ON_RESUME -> moveToVisibilityState(LithoVisibilityState.HINT_VISIBLE)
      Lifecycle.Event.ON_PAUSE -> moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
      Lifecycle.Event.ON_DESTROY -> {
        moveToVisibilityState(LithoVisibilityState.DESTROYED)
        lifecycleOwner.lifecycle.removeObserver(this)
      }
      else -> {}
    }
  }
}
