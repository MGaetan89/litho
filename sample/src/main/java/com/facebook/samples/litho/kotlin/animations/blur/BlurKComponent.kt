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

package com.facebook.samples.litho.kotlin.animations.blur

import com.facebook.litho.Column
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.useState
import com.facebook.litho.view.blur
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp
import com.facebook.samples.litho.kotlin.animations.dynamicprops.SeekBar
import com.facebook.samples.litho.kotlin.primitives.SimpleImageViewPrimitiveComponent

class BlurKComponent : KComponent() {

  override fun ComponentScope.render(): Component? {
    val radius = useState { 0f }
    return Column(style = Style.padding(all = 20.dp)) {
      child(
          SeekBar(
              initialValue = 0f,
              label = "Blur Radius",
              onProgressChanged = { radius.update(it * 5f) }))
      // text component that mounts a drawable
      child(
          Text(
              text = "Hello",
              textSize = 20.sp,
              style =
                  Style.margin(all = 10.dp).blur(radiusX = radius.value, radiusY = radius.value)))
      // image component that mounts a view
      child(
          SimpleImageViewPrimitiveComponent(
              style =
                  Style.width(100.dp)
                      .height(100.dp)
                      .margin(all = 10.dp)
                      .blur(radiusX = radius.value, radiusY = radius.value)))
    }
  }
}
