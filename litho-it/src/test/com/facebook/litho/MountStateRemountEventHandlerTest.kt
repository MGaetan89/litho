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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.config.TempComponentsConfigurations
import com.facebook.litho.testing.eventhandler.EventHandlerTestHelper
import com.facebook.litho.testing.helper.ComponentTestHelper
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class MountStateRemountEventHandlerTest {

  private lateinit var context: ComponentContext

  @Before
  fun setup() {
    TempComponentsConfigurations.setShouldAddHostViewForRootComponent(true)
    context = ComponentContext(ApplicationProvider.getApplicationContext<Context>())
  }

  @After
  fun restoreConfiguration() {
    TempComponentsConfigurations.restoreShouldAddHostViewForRootComponent()
  }

  @Test
  fun testReuseLongClickListenerOnSameView() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .longClickHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<LongClickEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(1)
    val longClickListener = ViewAttributes.getComponentLongClickListener(lithoView.getChildAt(0))
    assertThat(longClickListener).isNotNull
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .longClickHandler(
                      EventHandlerTestHelper.create(c, 1) as? EventHandler<LongClickEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(
            longClickListener ===
                ViewAttributes.getComponentLongClickListener(lithoView.getChildAt(0)))
        .isTrue
  }

  @Test
  fun testReuseFocusChangeListenerListenerOnSameView() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .focusChangeHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<FocusChangedEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(1)
    val focusChangeListener =
        ViewAttributes.getComponentFocusChangeListener(lithoView.getChildAt(0))
    assertThat(focusChangeListener).isNotNull
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .focusChangeHandler(
                      EventHandlerTestHelper.create(c, 1) as? EventHandler<FocusChangedEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(
            focusChangeListener ===
                ViewAttributes.getComponentFocusChangeListener(lithoView.getChildAt(0)))
        .isTrue
  }

  @Test
  fun testReuseTouchListenerOnSameView() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .touchHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<TouchEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(1)
    val touchListener = ViewAttributes.getComponentTouchListener(lithoView.getChildAt(0))
    assertThat(touchListener).isNotNull
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .touchHandler(EventHandlerTestHelper.create(c, 2) as? EventHandler<TouchEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(ViewAttributes.getComponentTouchListener(lithoView.getChildAt(0)))
        .isEqualTo(touchListener)
  }

  @Test
  fun testUnsetClickHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .clickHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<ClickEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.getChildAt(0).isClickable).isTrue
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.isClickable).isFalse
  }

  @Test
  fun testUnsetLongClickHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .longClickHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<LongClickEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(1)
    assertThat(ViewAttributes.getComponentLongClickListener(lithoView.getChildAt(0))).isNotNull
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(0)
    val listener = ViewAttributes.getComponentLongClickListener(lithoView)
    assertThat(listener).isNull()
  }

  @Test
  fun testUnsetFocusChangeHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .focusChangeHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<FocusChangedEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(1)
    assertThat(ViewAttributes.getComponentFocusChangeListener(lithoView.getChildAt(0))).isNotNull
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(0)
    val listener = ViewAttributes.getComponentFocusChangeListener(lithoView)
    assertThat(listener).isNull()
  }

  @Test
  fun testUnsetTouchHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .touchHandler(
                          EventHandlerTestHelper.create(c, 1) as? EventHandler<TouchEvent>)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(0)
    val listener = ViewAttributes.getComponentTouchListener(lithoView)
    assertThat(listener).isNull()
  }

  @Test
  fun testSetClickHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(0)
    assertThat(lithoView.isClickable).isFalse
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .clickHandler(EventHandlerTestHelper.create(c, 1) as? EventHandler<ClickEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(1)
    assertThat(lithoView.getChildAt(0).isClickable).isTrue
  }

  @Test
  fun testSetLongClickHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(0)
    assertThat(ViewAttributes.getComponentLongClickListener(lithoView)).isNull()
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .longClickHandler(
                      EventHandlerTestHelper.create(c, 1) as? EventHandler<LongClickEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(1)
    val listener = ViewAttributes.getComponentLongClickListener(lithoView.getChildAt(0))
    assertThat(listener).isNotNull
    assertThat(listener?.eventHandler).isNotNull
  }

  @Test
  fun testSetFocusChangeHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(0)
    assertThat(ViewAttributes.getComponentFocusChangeListener(lithoView)).isNull()
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .focusChangeHandler(
                      EventHandlerTestHelper.create(c, 1) as? EventHandler<FocusChangedEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(1)
    val listener = ViewAttributes.getComponentFocusChangeListener(lithoView.getChildAt(0))
    assertThat(listener).isNotNull
    assertThat(listener?.eventHandler).isNotNull
  }

  @Test
  fun testSetTouchHandler() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12_345
    val lithoView =
        ComponentTestHelper.mountComponent(
            context,
            object : InlineLayoutSpec(COMPONENT_IDENTITY) {
              override fun onCreateLayout(c: ComponentContext): Component =
                  Column.create(c)
                      .child(SimpleMountSpecTester.create(c))
                      .child(SimpleMountSpecTester.create(c))
                      .build()
            })
    assertThat(lithoView.childCount).isEqualTo(0)
    assertThat(ViewAttributes.getComponentTouchListener(lithoView)).isNull()
    lithoView.componentTree?.root =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component =
              Column.create(c)
                  .touchHandler(EventHandlerTestHelper.create(c, 1) as? EventHandler<TouchEvent>)
                  .child(SimpleMountSpecTester.create(c))
                  .child(SimpleMountSpecTester.create(c))
                  .build()
        }
    assertThat(lithoView.childCount).isEqualTo(1)
    val listener = ViewAttributes.getComponentTouchListener(lithoView.getChildAt(0))
    assertThat(listener).isNotNull
    assertThat(listener?.eventHandler).isNotNull
  }
}
