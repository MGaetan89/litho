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

package com.facebook.widget.accessibility.delegates

import android.text.TextPaint
import android.text.style.CharacterStyle

/**
 * Extends the CharacterStyle class to include a dedicated field for an accessibility content
 * description. This is useful in cases where the spanned content either cannot be described via the
 * spanned text alone (for example, an image) or when the text of the span could use extra
 * clarification for users of accessibility services like screen readers.
 *
 * For example, some text that says "Click the button above to continue" may not be descriptive
 * enough for a user without the visual context of which button is above the text. You could use
 * this span to change "button above" to something more descriptive like "next step button" without
 * changing the visual text.
 *
 * ```
 * val sb: SpannableStringBuilder = SpannableStringBuilder("Click the button above to continue")
 * sb.setSpan(ContentDescriptionSpan("next step button"), 10, 22, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
 *
 * Text.create(c).text(sb).build()
 * ```
 */
class ContentDescriptionSpan(var contentDescription: String?) : CharacterStyle() {
  override fun updateDrawState(tp: TextPaint): Unit = Unit
}
