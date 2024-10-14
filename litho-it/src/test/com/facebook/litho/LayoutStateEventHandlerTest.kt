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

import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLayoutComponent
import com.facebook.litho.testing.eventhandler.EventHandlerTestHelper
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class LayoutStateEventHandlerTest {

  @JvmField @Rule val mLithoTestRule: LithoTestRule = LithoTestRule()

  private var rootComponent: Component? = null
  private var nestedComponent: Component? = null

  @Before
  fun setup() {
    rootComponent =
        object : InlineLayoutSpec() {
          protected override fun onCreateLayout(c: ComponentContext): Component {
            assertCorrectEventHandler(EventHandlerTestHelper.create(c, 1), 1, rootComponent)
            Wrapper.create(c).delegate(nestedComponent).build()
            assertCorrectEventHandler(EventHandlerTestHelper.create(c, 2), 2, rootComponent)
            Wrapper.create(c).delegate(nestedComponent).build()
            assertCorrectEventHandler(EventHandlerTestHelper.create(c, 3), 3, rootComponent)
            return TestLayoutComponent.create(c).build()
          }
        }
    nestedComponent =
        object : InlineLayoutSpec() {
          protected override fun onCreateLayout(c: ComponentContext): Component {
            assertCorrectEventHandler(EventHandlerTestHelper.create(c, 1), 1, nestedComponent)
            return TestLayoutComponent.create(c).build()
          }
        }
  }

  @Test
  fun testNestedEventHandlerInput() {
    mLithoTestRule.render { rootComponent }
  }

  companion object {
    private fun assertCorrectEventHandler(
        eventHandler: EventHandler<*>,
        expectedId: Int,
        expectedInput: Component?
    ) {
      Assertions.assertThat(eventHandler.dispatchInfo.hasEventDispatcher).isEqualTo(expectedInput)
      Assertions.assertThat(eventHandler.id).isEqualTo(expectedId)
    }
  }
}
