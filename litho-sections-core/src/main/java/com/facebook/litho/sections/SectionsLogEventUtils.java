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

package com.facebook.litho.sections;

import androidx.annotation.IntDef;
import com.facebook.infer.annotation.Nullsafe;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Utility class for creating common logging events. */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class SectionsLogEventUtils {

  @IntDef({
    ApplyNewChangeSet.NONE,
    ApplyNewChangeSet.SET_ROOT,
    ApplyNewChangeSet.SET_ROOT_ASYNC,
    ApplyNewChangeSet.UPDATE_STATE,
    ApplyNewChangeSet.UPDATE_STATE_ASYNC
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface ApplyNewChangeSet {
    int NONE = -1;
    int SET_ROOT = 0;
    int SET_ROOT_ASYNC = 1;
    int UPDATE_STATE = 2;
    int UPDATE_STATE_ASYNC = 3;
  }

  public static String applyNewChangeSetSourceToString(@ApplyNewChangeSet int source) {
    switch (source) {
      case ApplyNewChangeSet.NONE:
        return "none";
      case ApplyNewChangeSet.SET_ROOT:
        return "setRoot";
      case ApplyNewChangeSet.SET_ROOT_ASYNC:
        return "setRootAsync";
      case ApplyNewChangeSet.UPDATE_STATE:
        return "updateState";
      case ApplyNewChangeSet.UPDATE_STATE_ASYNC:
        return "updateStateAsync";
      default:
        throw new IllegalStateException("Unknown source");
    }
  }
}
